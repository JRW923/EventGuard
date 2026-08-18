"""AI HTTP 端点鉴权测试：JWT 缺失/非法/缺权限/正常。"""
import jwt as pyjwt

from fastapi.testclient import TestClient

from app.config import settings
from app.main import app

client = TestClient(app)


def _token(permissions, secret=None, algo="HS256"):
    payload = {"username": "tester", "permissions": permissions}
    return pyjwt.encode(payload, secret or settings.jwt_secret, algorithm=algo)


def test_ai_query_rejects_missing_token():
    resp = client.post("/ai/query", json={"question": "订单状态？"})
    assert resp.status_code == 401


def test_ai_query_rejects_invalid_token():
    resp = client.post("/ai/query", json={"question": "订单状态？"},
                       headers={"Authorization": "Bearer not-a-jwt"})
    assert resp.status_code == 401


def test_ai_query_rejects_token_without_permission():
    token = _token(["order:read"])  # 无 ai:query
    resp = client.post("/ai/query", json={"question": "订单状态？"},
                       headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 403


def test_ai_query_accepts_valid_token():
    token = _token(["ai:query"])
    resp = client.post("/ai/query", json={"question": "订单状态？"},
                       headers={"Authorization": f"Bearer {token}"})
    # 鉴权通过（非 401/403）；引擎内部行为取决于环境，不在此断言
    assert resp.status_code not in (401, 403)


def test_analysis_rejects_token_without_permission():
    token = _token(["ai:query"])  # 无 anomaly:view
    resp = client.get("/anomalies/nonexistent/analysis",
                      headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 403


def test_analysis_requires_anomaly_view():
    token = _token(["anomaly:view"])
    resp = client.get("/anomalies/nonexistent/analysis",
                      headers={"Authorization": f"Bearer {token}"})
    # 鉴权通过后 404（异常不存在），而非 401/403
    assert resp.status_code == 404


def test_health_is_public():
    resp = client.get("/health")
    assert resp.status_code == 200
