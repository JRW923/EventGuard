"""终局预测端点短路测试：终态订单终局已知，不做零信息推理。"""
import jwt as pyjwt
from fastapi.testclient import TestClient

import app.main as main
from app.config import settings
from app.main import app

client = TestClient(app)

AGG_ID = "00000000-0000-0000-0000-000000000000"


def _token(permissions=("ai:query",)):
    return pyjwt.encode({"username": "tester", "permissions": list(permissions)},
                        settings.jwt_secret, algorithm="HS256")


class _TerminalPredictor:
    """若被调用即失败：证明终态短路没有触发推理。"""

    available = True

    def predict_order(self, aggregate_id):
        raise AssertionError("终态订单不应触发模型推理")


def _patch_backend(monkeypatch, status):
    class _Backend:
        async def get_order(self, aggregate_id):
            return {"status": status}

    monkeypatch.setattr(main, "BackendClient", _Backend)


def test_terminal_order_short_circuits(monkeypatch):
    _patch_backend(monkeypatch, "CLOSED")
    monkeypatch.setattr(main, "_predictor", _TerminalPredictor())

    resp = client.get(f"/ai/predict/{AGG_ID}",
                      headers={"Authorization": f"Bearer {_token()}"})

    assert resp.status_code == 200
    body = resp.json()
    assert body["prediction"] is None
    assert body["current_status"] == "CLOSED"
    assert "终态" in body["message"]


def test_inflight_order_still_predicts(monkeypatch):
    _patch_backend(monkeypatch, "PAID")

    class _Predictor:
        available = True

        def predict_order(self, aggregate_id):
            return {"outcome": "CLOSED", "confidence": 0.9, "risk": "LOW"}

    monkeypatch.setattr(main, "_predictor", _Predictor())

    resp = client.get(f"/ai/predict/{AGG_ID}",
                      headers={"Authorization": f"Bearer {_token()}"})

    assert resp.status_code == 200
    assert resp.json()["prediction"]["outcome"] == "CLOSED"
