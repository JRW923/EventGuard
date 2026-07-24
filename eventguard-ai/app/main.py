import httpx
from fastapi import FastAPI, HTTPException

from app.analyzer.root_cause import RootCauseAnalyzer, LLMResponseError
from app.config import settings
from app.store.anomaly_store import anomaly_store

app = FastAPI(title=settings.app_name)

_analyzer = RootCauseAnalyzer()


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/anomalies/{anomaly_id}/analysis")
def get_analysis(anomaly_id: str):
    """根因分析：通过 anomaly_id 查找异常并生成分析报告"""
    anomaly = anomaly_store.get(anomaly_id)
    if anomaly is None:
        raise HTTPException(status_code=404, detail=f"异常 {anomaly_id} 不存在")

    try:
        report = _analyzer.analyze(anomaly)
    except LLMResponseError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail="LLM 服务不可用")
    return report.model_dump()
