package com.eventguard.auth.dto;

/** 登录成功响应：JWT + 用户信息（前端存 token 并据此做路由/按钮级鉴权）。 */
public record LoginResponse(String token, UserView user) {
}
