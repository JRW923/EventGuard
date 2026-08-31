import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from typing import Optional
import httpx
from fastapi import FastAPI, HTTPException, Depends
from fastapi.responses import Response
from pydantic import BaseModel
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import metrics as egm
from app.analyzer.healer_agent import HealerAgent
from app.analyzer.llm_client import LLMClient
from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
from app.cases.case_index import CaseIndex
from app.config import TERMINAL_ORDER_STATUSES, settings
from app.detector.event_level import EventLevelService
from app.detector.event_window import EventWindow
from app.detector.process_level import ProcessLevelRuleDetector
from app.detector.process_level_hmm import ProcessLevelHMMDetector
from app.kafka_consumer import DetectionHandler, EventKafkaConsumer
from app.publisher.anomaly_publisher import AnomalyPublisher
from app.predictor.order_predictor import OrderPredictor
from app.query.backend_client import BackendClient
from app.query.intent_classifier import IntentClassifier
from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult
from app.report.story_generator import StoryGenerator
from app.report.weekly_report import WeeklyReportGenerator
from app.security import require_permission
from app.store.anomaly_store import anomaly_store
from app.trace.trace_log import trace_log

logger = logging.getLogger(__name__)

# 检测管道全局引用：启动时在 lifespan 中装配，shutdown 时停止
_consumer: EventKafkaConsumer | None = None
_publisher: AnomalyPublisher | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动时装配 Kafka 事件检测管道（事件级 + 流程级），使 domain-events → anomaly-alerts 闭环真正运行。"""
    global _consumer, _publisher
    try:
        # 事件级：规则引擎（HTTP，高优）→ Isolation Forest（低优）；流程级：规则 + HMM（可选）
        event_level_service = EventLevelService()
        event_window = EventWindow(window_size=20)
        process_detector = ProcessLevelRuleDetector()
        _publisher = AnomalyPublisher()
        handler = DetectionHandler(
            event_level_service=event_level_service,
            publisher=_publisher,
            process_level_detector=process_detector,
            event_window=event_window,
            # HMM 序列级第二意见：模型文件缺失时构造函数自行降级（loaded=False，detect 返回 []）
            hmm_detector=ProcessLevelHMMDetector(),
        )
        # EventKafkaConsumer 的 handler 参数是可调用对象：传 handler.handle 而非实例本身
        _consumer = EventKafkaConsumer(handler=handler.handle)
        _consumer.start()
        egm.detector_running.set(1)
        logger.info("AI 检测管道已启动：消费 domain-events → 检测 → 发布 anomaly-alerts")
    except Exception as e:
        # ponytail: 检测管道启动失败不应拖垮 API（NL 查询 / 根因分析仍可用），降级为关闭状态
        logger.exception("AI 检测管道启动失败（降级：仅提供 API，不消费事件）: %s", e)
        _consumer = None
        _publisher = None
        egm.detector_running.set(0)
    try:
        yield
    finally:
        if _consumer is not None:
            _consumer.stop()
        if _publisher is not None:
            _publisher.close()
        egm.detector_running.set(0)
        logger.info("AI 检测管道已停止")


app = FastAPI(title=settings.app_name, lifespan=lifespan)


class MissingLlmConfig(Exception):
    """用户未配置 LLM 时抛出，由端点层转为明确的 409 提示。"""


async def _llm_client_for(principal: dict) -> LLMClient:
    """按请求用户构造 LLMClient：从 Java 侧拉取该用户解密后的 LLM 配置。

    配置存在 Java 侧 user_llm_config 表（每用户独立，API key AES 加密），
    AI 服务以机器密钥读取，不再使用任何进程级环境默认值。
    """
    uid = principal.get("uid")
    if uid is None:
        raise MissingLlmConfig()
    try:
        cfg = await BackendClient().get_user_llm_config(uid)
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            raise MissingLlmConfig()
        raise
    return LLMClient(
        base_url=cfg["base_url"],
        api_key=cfg["api_key"],
        model=cfg["model"],
        max_tokens=cfg.get("max_tokens"),
        temperature=cfg.get("temperature"),
        provider=cfg.get("provider") or "",
    )


class NLQueryRequest(BaseModel):
    question: str
    # 多轮对话会话 id：无则后端新建（响应里返回），前端续聊时携带
    conversation_id: Optional[str] = None


@app.post("/ai/query", response_model=QueryResult)
async def ai_query(req: NLQueryRequest, response: Response,
                   principal: dict = Depends(require_permission("ai:query"))):
    """自然语言查询：意图分类 + 模板查询 + LLM 润色；缺参时反问（多轮对话）。"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    try:
        llm_client = await _llm_client_for(principal)
    except MissingLlmConfig:
        # NL 查询的 LLM 是可选润色：用户未配置时降级为关键词意图 + 数据摘要，仍可回答
        llm_client = None
    engine = NLQueryEngine(
        intent_classifier=IntentClassifier(llm_client=llm_client),
        llm_client=llm_client,
    )
    return await engine.query(req.question, req.conversation_id, trace_id=trace_id)


@app.get("/ai/traces/recent")
async def ai_traces_recent(limit: int = 100, _: dict = Depends(require_permission("ai:query"))):
    """AI 可观测性：最近 N 条操作 trace（llm_call / nl_query / root_cause / llm_cache）。"""
    return trace_log.recent(limit=limit)


# 预测器惰性单例（模型缺失时 available=False，端点返回 prediction=null）
_predictor = None


def _get_predictor() -> OrderPredictor:
    global _predictor
    if _predictor is None:
        _predictor = OrderPredictor()
    return _predictor


@app.get("/ai/predict/{aggregate_id}")
async def predict_order(aggregate_id: str, _: dict = Depends(require_permission("ai:query"))):
    """订单终局预测：加载事件序列 → 预测终局状态 + 置信度 + 风险分级。"""
    predictor = _get_predictor()
    if not predictor.available:
        return {"aggregate_id": aggregate_id, "prediction": None,
                "message": "预测模型不可用（models/predictor.pkl 缺失）"}
    current_status = None
    try:
        order = await BackendClient().get_order(aggregate_id)
        current_status = order.get("status")
    except Exception as exc:
        # 当前状态只是展示用的补充信息，取不到不影响预测结果，但必须留下痕迹
        logger.warning("取订单当前状态失败 aggregate_id=%s: %s", aggregate_id, exc)
    # 终态订单终局已知：短路返回，不做零信息推理（与前端隐藏按钮、watchlist 过滤同一口径）
    if current_status in TERMINAL_ORDER_STATUSES:
        return {"aggregate_id": aggregate_id, "current_status": current_status,
                "prediction": None, "message": f"订单已在终态 {current_status}，终局已知，无需预测"}
    pred = predictor.predict_order(aggregate_id)
    return {"aggregate_id": aggregate_id, "current_status": current_status, "prediction": pred}


@app.get("/ai/predictions/watchlist")
async def predictions_watchlist(limit: int = 10, _: dict = Depends(require_permission("ai:query"))):
    """高风险在途订单 watchlist：遍历后端非终态订单批量预测，按风险降序返回 TopN。"""
    predictor = _get_predictor()
    if not predictor.available:
        return {"items": [], "message": "预测模型不可用（models/predictor.pkl 缺失）"}
    data = await BackendClient().list_orders(size=50)
    orders = data.get("orders", [])
    items = []
    for o in orders:
        if o.get("status") in TERMINAL_ORDER_STATUSES:
            continue
        try:
            pred = predictor.predict_order(o.get("orderId", ""))
        except Exception as exc:
            # 单笔预测失败不该让整个 watchlist 变空，跳过并记录
            logger.warning("watchlist 单笔预测失败 orderId=%s: %s", o.get("orderId"), exc)
            pred = None
        if pred:
            items.append({"orderId": o.get("orderId"), "status": o.get("status"), **pred})
    items.sort(key=lambda x: OrderPredictor.risk_rank(x.get("risk", "LOW")))
    return {"items": items[:limit]}


class WeeklyReportRequest(BaseModel):
    days: int = 7


@app.post("/ai/report/weekly")
async def weekly_report(
    req: WeeklyReportRequest, principal: dict = Depends(require_permission("ai:query"))
):
    """运营周报：近期异常聚合 + 订单统计 + LLM 症状/建议（Item 7）。"""
    try:
        llm_client = await _llm_client_for(principal)
    except MissingLlmConfig:
        raise HTTPException(status_code=409, detail="请先在个人中心配置你的 LLM API")
    return await WeeklyReportGenerator(llm_client=llm_client).generate(req.days)


@app.get("/ai/orders/{aggregate_id}/story")
async def order_story(
    aggregate_id: str, principal: dict = Depends(require_permission("ai:query"))
):
    """订单事件故事线：事件链 → 运营可读复盘（Item 7）。"""
    try:
        llm_client = await _llm_client_for(principal)
    except MissingLlmConfig:
        raise HTTPException(status_code=409, detail="请先在个人中心配置你的 LLM API")
    return await StoryGenerator(llm_client=llm_client).generate(aggregate_id)


# 相似案例检索惰性单例（Item 8 · 轻量 RAG）
_case_index = None


def _get_case_index() -> CaseIndex:
    global _case_index
    if _case_index is None:
        _case_index = CaseIndex()
    return _case_index


@app.get("/ai/cases/{anomaly_id}/similar")
async def similar_cases(
    anomaly_id: str,
    top_k: int = 5,
    _: dict = Depends(require_permission("anomaly:view")),
):
    """相似案例检索：按规则/事件类型/时间近邻打分，附上次处置状态（Item 8）。"""
    return await _get_case_index().query(anomaly_id, top_k)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "detector": {
            "running": _consumer is not None and _consumer._running,
            "topic": _consumer.topic if _consumer is not None else None,
            "group_id": _consumer.group_id if _consumer is not None else None,
        },
    }


@app.get("/metrics")
def metrics():
    """Prometheus 抓取端点（prometheus.yml 已配置 eventguard-ai job）。"""
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/anomalies/{anomaly_id}/analysis")
async def get_analysis(
    anomaly_id: str, response: Response, principal: dict = Depends(require_permission("anomaly:view"))
):
    """根因分析：通过 anomaly_id 查找异常并生成分析报告"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")

    try:
        llm_client = await _llm_client_for(principal)
    except MissingLlmConfig:
        raise HTTPException(status_code=409, detail="请先在个人中心配置你的 LLM API")

    try:
        # 端到端超时：LLM 各层有自己的长超时，不设上界时单次分析可拖到分钟级
        report = await asyncio.wait_for(
            RootCauseAnalyzer(llm_client=llm_client).analyze(anomaly, trace_id=trace_id),
            timeout=settings.analysis_timeout_seconds,
        )
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="根因分析超时，请稍后重试")
    except LLMResponseError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail="LLM 服务不可用")
    return report.model_dump()


@app.post("/ai/heal/{anomaly_id}")
async def ai_heal(
    anomaly_id: str, response: Response, principal: dict = Depends(require_permission("anomaly:view"))
):
    """ReAct 根因分析：agent 多轮工具调用收集证据 → 结构化报告 + 分析过程 trace。"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")

    try:
        llm_client = await _llm_client_for(principal)
    except MissingLlmConfig:
        raise HTTPException(status_code=409, detail="请先在个人中心配置你的 LLM API")

    # 最多 5 步 × 每步 LLM 调用，不设上界时最坏可达数分钟；超时 504 比无限等待诚实
    try:
        healer = HealerAgent(
            llm_client=llm_client,
            root_cause_analyzer=RootCauseAnalyzer(llm_client=llm_client),
        )
        return await asyncio.wait_for(
            healer.heal(anomaly, trace_id=trace_id),
            timeout=settings.heal_timeout_seconds,
        )
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="自愈分析超时，请稍后重试")
