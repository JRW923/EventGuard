from fastapi import FastAPI, HTTPException

from app.analyzer.root_cause import RootCauseAnalyzer
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

    report = _analyzer.analyze(anomaly)
    return report.model_dump()
