"""JWT 鉴权：解析 Authorization: Bearer <JWT>，按权限码放行（与 Java 后端共用 EG_JWT_SECRET）。

浏览器经 nginx 转发 Authorization 头到 AI 服务；AI 侧独立校验签名/过期，并校验所需权限码
（/ai/query 需 ai:query，根因分析需 anomaly:view），避免信任上游代理。
"""
import logging

import jwt
from fastapi import Header, HTTPException

from app.config import settings

logger = logging.getLogger(__name__)


def require_permission(code: str):
    """FastAPI 依赖工厂：要求 Authorization Bearer 中的权限码包含 code，否则 401/403。"""

    async def dependency(authorization: str = Header(None)):
        token = None
        if authorization and authorization.startswith("Bearer "):
            token = authorization[len("Bearer "):].strip()
        if not token:
            raise HTTPException(status_code=401, detail="Missing or invalid token")
        try:
            payload = jwt.decode(token, settings.jwt_secret, algorithms=["HS256"])
        except jwt.PyJWTError:
            logger.warning("JWT 校验失败: %s", authorization[:24] + "..." if authorization else "")
            raise HTTPException(status_code=401, detail="Missing or invalid token")
        perms = payload.get("permissions") or []
        if code not in perms:
            raise HTTPException(status_code=403, detail=f"权限不足：{code}")
        return payload

    return dependency
