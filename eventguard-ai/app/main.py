import logging
from contextlib import asynccontextmanager
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, Depends
from fastapi.responses import Response
from pydantic import BaseModel
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import metrics as egm
from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
from app.config import settings
from app.detector.event_level import EventLevelService
from app.detector.event_window import EventWindow
from app.detector.process_level import ProcessLevelRuleDetector
from app.kafka_consumer import DetectionHandler, EventKafkaConsumer
from app.publisher.anomaly_publisher import AnomalyPublisher
from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult
from app.security import require_permission
from app.store.anomaly_store import anomaly_store

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

_analyzer = RootCauseAnalyzer()


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
async def ai_query(req: NLQueryRequest, _: dict = Depends(require_permission("ai:query"))):
    """自然语言查询：意图分类 + 模板查询 + LLM 润色；缺参时反问（多轮对话）。"""
    engine = _get_nl_query_engine()
    return await engine.query(req.question, req.conversation_id)


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
async def get_analysis(anomaly_id: str, _: dict = Depends(require_permission("anomaly:view"))):
    """根因分析：通过 anomaly_id 查找异常并生成分析报告"""
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")

    try:
        report = await _analyzer.analyze(anomaly)
    except LLMResponseError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail="LLM 服务不可用")
    return report.model_dump()
