import httpx
from fastapi import FastAPI, HTTPException, Depends
from pydantic import BaseModel

from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
from app.config import settings
from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult
from app.security import require_permission
from app.store.anomaly_store import anomaly_store

app = FastAPI(title=settings.app_name)

_analyzer = RootCauseAnalyzer()


class NLQueryRequest(BaseModel):
    question: str


# 单例引擎（首次调用时初始化）
_nl_query_engine = None


def _get_nl_query_engine() -> NLQueryEngine:
    global _nl_query_engine
    if _nl_query_engine is None:
        _nl_query_engine = NLQueryEngine()
    return _nl_query_engine


@app.post("/ai/query", response_model=QueryResult)
async def ai_query(req: NLQueryRequest, _: dict = Depends(require_permission("ai:query"))):
    """自然语言查询：意图分类 + 模板查询 + LLM 润色。"""
    engine = _get_nl_query_engine()
    return await engine.query(req.question)


@app.get("/health")
def health():
    return {"status": "ok"}


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
