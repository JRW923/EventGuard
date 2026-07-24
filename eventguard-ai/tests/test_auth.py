"""AI 服务 API Key 鉴权测试。"""
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


def test_ai_query_rejects_missing_key():
    client = TestClient(app)
    resp = client.post("/ai/query", json={"question": "订单状态？"})
    assert resp.status_code == 401


def test_ai_query_accepts_valid_key():
    client = TestClient(app)
    resp = client.post(
        "/ai/query",
        json={"question": "订单状态？"},
        headers={"X-API-Key": settings.api_key},
    )
    # 鉴权层通过即可（业务失败另算），只验证非 401
    assert resp.status_code != 401
