import logging
import uuid
from contextlib import asynccontextmanager
from typing import Optional
from urllib.parse import urlparse

import httpx
from fastapi import FastAPI, HTTPException, Depends
from fastapi.responses import Response
from pydantic import BaseModel, Field, field_validator
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import metrics as egm
from app.analyzer.healer_agent import HealerAgent
from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
from app.cases.case_index import CaseIndex
from app.config import default_llm_config, llm_config, reset_llm_config, settings, update_llm_config
from app.detector.event_level import EventLevelService
from app.detector.event_window import EventWindow
from app.detector.process_level import ProcessLevelRuleDetector
from app.kafka_consumer import DetectionHandler, EventKafkaConsumer
from app.publisher.anomaly_publisher import AnomalyPublisher
from app.predictor.order_predictor import OrderPredictor
from app.query.backend_client import BackendClient
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
            # HMM 缺文件时 detect 返回 []，不阻断主流程
            hmm_detector=None,
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

_analyzer: RootCauseAnalyzer | None = None


def _get_analyzer() -> RootCauseAnalyzer:
    global _analyzer
    if _analyzer is None:
        _analyzer = RootCauseAnalyzer()
    return _analyzer


def _reset_llm_services() -> None:
    """切换 provider/model 后丢弃惰性单例，让后续请求使用新配置。"""
    global _analyzer, _nl_query_engine, _report_gen, _story_gen, _healer
    _analyzer = None
    _nl_query_engine = None
    _report_gen = None
    _story_gen = None
    _healer = None


class LlmSettingsUpdate(BaseModel):
    provider: str = Field(default="", max_length=32)
    base_url: str = Field(min_length=8, max_length=500)
    api_key: Optional[str] = Field(default=None, max_length=1000)
    model: str = Field(min_length=1, max_length=200)
    max_tokens: int = Field(default=2048, ge=128, le=32768)
    temperature: float = Field(default=0.3, ge=0, le=2)

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        value = value.strip().rstrip("/")
        if not value.startswith(("http://", "https://")):
            raise ValueError("base_url 必须以 http:// 或 https:// 开头")
        parsed = urlparse(value)
        if parsed.username or parsed.password:
            raise ValueError("base_url 不应包含账号或密码，请单独填写 API key")
        return value

    @field_validator("provider")
    @classmethod
    def normalize_provider(cls, value: str) -> str:
        value = value.strip().lower()
        if value not in {"", "openai", "anthropic"}:
            raise ValueError("provider 仅支持 openai、anthropic 或自动探测")
        return value


def _masked_key(key: str) -> str:
    if not key:
        return ""
    if len(key) <= 4:
        return "****"
    return "*" * max(0, len(key) - 4) + key[-4:]


def _public_llm_config() -> dict:
    current = llm_config()
    defaults = default_llm_config()
    return {
        "provider": current["llm_provider"],
        "base_url": current["llm_base_url"],
        "model": current["llm_model"],
        "max_tokens": current["llm_max_tokens"],
        "temperature": current["llm_temperature"],
        "api_key_masked": _masked_key(current["llm_api_key"]),
        "has_api_key": bool(current["llm_api_key"]),
        "using_defaults": current == defaults,
    }


@app.get("/ai/settings/llm")
async def get_llm_settings(_: dict = Depends(require_permission("user:manage"))):
    return _public_llm_config()


@app.put("/ai/settings/llm")
async def put_llm_settings(req: LlmSettingsUpdate, _: dict = Depends(require_permission("user:manage"))):
    values = {
        "llm_provider": req.provider,
        "llm_base_url": req.base_url,
        "llm_model": req.model,
        "llm_max_tokens": req.max_tokens,
        "llm_temperature": req.temperature,
    }
    # 空字符串表示沿用当前 key，避免编辑其他字段时意外清空密钥。
    if req.api_key is not None and req.api_key.strip():
        values["llm_api_key"] = req.api_key.strip()
    update_llm_config(values)
    _reset_llm_services()
    return _public_llm_config()


@app.post("/ai/settings/llm/reset")
async def post_llm_settings_reset(_: dict = Depends(require_permission("user:manage"))):
    reset_llm_config()
    _reset_llm_services()
    return _public_llm_config()


class NLQueryRequest(BaseModel):
    question: str
    # 多轮对话会话 id：无则后端新建（响应里返回），前端续聊时携带
    conversation_id: Optional[str] = None


# 单例引擎（首次调用时初始化）
_nl_query_engine = None


def _get_nl_query_engine() -> NLQueryEngine:
    global _nl_query_engine
    if _nl_query_engine is None:
        _nl_query_engine = NLQueryEngine()
    return _nl_query_engine


@app.post("/ai/query", response_model=QueryResult)
async def ai_query(req: NLQueryRequest, response: Response, _: dict = Depends(require_permission("ai:query"))):
    """自然语言查询：意图分类 + 模板查询 + LLM 润色；缺参时反问（多轮对话）。"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    engine = _get_nl_query_engine()
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
    pred = predictor.predict_order(aggregate_id)
    current_status = None
    try:
        order = await BackendClient().get_order(aggregate_id)
        current_status = order.get("status")
    except Exception:
        pass
    return {"aggregate_id": aggregate_id, "current_status": current_status, "prediction": pred}


@app.get("/ai/predictions/watchlist")
async def predictions_watchlist(limit: int = 10, _: dict = Depends(require_permission("ai:query"))):
    """高风险在途订单 watchlist：遍历后端非终态订单批量预测，按风险降序返回 TopN。"""
    predictor = _get_predictor()
    if not predictor.available:
        return {"items": [], "message": "预测模型不可用（models/predictor.pkl 缺失）"}
    data = await BackendClient().list_orders(size=50)
    orders = data.get("orders", [])
    terminal = {"CLOSED", "CANCELLED"}
    items = []
    for o in orders:
        if o.get("status") in terminal:
            continue
        try:
            pred = predictor.predict_order(o.get("orderId", ""))
        except Exception:
            pred = None
        if pred:
            items.append({"orderId": o.get("orderId"), "status": o.get("status"), **pred})
    items.sort(key=lambda x: OrderPredictor.risk_rank(x.get("risk", "LOW")))
    return {"items": items[:limit]}


# 周报 / 故事线惰性单例（Item 7）
_report_gen = None
_story_gen = None


def _get_report_generator() -> WeeklyReportGenerator:
    global _report_gen
    if _report_gen is None:
        _report_gen = WeeklyReportGenerator()
    return _report_gen


def _get_story_generator() -> StoryGenerator:
    global _story_gen
    if _story_gen is None:
        _story_gen = StoryGenerator()
    return _story_gen


class WeeklyReportRequest(BaseModel):
    days: int = 7


@app.post("/ai/report/weekly")
async def weekly_report(
    req: WeeklyReportRequest, _: dict = Depends(require_permission("ai:query"))
):
    """运营周报：近期异常聚合 + 订单统计 + LLM 症状/建议（Item 7）。"""
    return await _get_report_generator().generate(req.days)


@app.get("/ai/orders/{aggregate_id}/story")
async def order_story(
    aggregate_id: str, _: dict = Depends(require_permission("ai:query"))
):
    """订单事件故事线：事件链 → 运营可读复盘（Item 7）。"""
    return await _get_story_generator().generate(aggregate_id)


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
    anomaly_id: str, response: Response, _: dict = Depends(require_permission("anomaly:view"))
):
    """根因分析：通过 anomaly_id 查找异常并生成分析报告"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")

    try:
        report = await _get_analyzer().analyze(anomaly, trace_id=trace_id)
    except LLMResponseError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail="LLM 服务不可用")
    return report.model_dump()


# ReAct 自愈 agent 惰性单例（Item 6a）
_healer = None


def _get_healer() -> HealerAgent:
    global _healer
    if _healer is None:
        _healer = HealerAgent()
    return _healer


@app.post("/ai/heal/{anomaly_id}")
async def ai_heal(
    anomaly_id: str, response: Response, _: dict = Depends(require_permission("anomaly:view"))
):
    """ReAct 根因分析：agent 多轮工具调用收集证据 → 结构化报告 + 分析过程 trace。"""
    trace_id = str(uuid.uuid4())
    response.headers["X-Trace-Id"] = trace_id
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")
    return await _get_healer().heal(anomaly, trace_id=trace_id)
