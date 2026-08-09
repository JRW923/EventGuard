# EventGuard V2 已知上限项 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 MVP 阶段刻意留白的 5 项已知上限（端点鉴权、补偿命令处理器 Spring 化、AI 同步阻塞改异步、未使用 import 清理、Git LFS 瘦身），并将 3 项已在代码中完成的前置项显式确认收口。

**Architecture:** 鉴权采用零新依赖方案——服务端用 `jakarta.servlet.Filter`（共用 `ApiKeyValidator`）校验 `X-API-Key`，WebSocket 握手用复用同一 `Validator` 的 `HandshakeInterceptor`（浏览器 WS 无法带自定义头，密钥走 `?api_key=` 查询参数）；AI 服务用 FastAPI `Depends` 校验同款密钥；前端通过 `import.meta.env.VITE_API_KEY` 注入。AI 异步化将同步 `httpx.Client` 全链路改为 `httpx.AsyncClient`（`pytest-asyncio` 已在依赖中）。补偿命令处理器由 `new` 改为 Spring `@Component` 注入。

**Tech Stack:** Java 17 + Spring Boot 3.3 (jakarta.servlet) · Python 3.11 + FastAPI + httpx + pytest-asyncio · Vue3 + Vite + axios · Docker / nginx / Git LFS

## 已确认完成（无需改动，收口即可）

规划时复查代码，以下 3 项**已在 main 中实现**，不计入任务，仅做收口确认：

- **原 #2 X-Command-Id 幂等**：`OrderCommandController` 已读 `X-Command-Id` 头并解析 UUID；`OrderCommandHandler.execute` 已先 `commandLogRepository.loadResult` 命中即返回，且 `command_log` 表在 `V1__init.sql` 与 `postgres-init/00-schema.sql` 均已建表。✅
- **原 #3 `OrderViewProjection.on()` @Transactional**：`OrderViewProjection.java:40` 已标注 `@Transactional`。✅
- **原 #4 Debezium ExtractNewRecordState SMT**：`debezium/conf/application.properties:13-14` 已配置 `transforms=unwrap` + `ExtractNewRecordState`，与 `EventDeserializer.deserializeFromKafka` 的扁平字段假设一致。✅

**收口动作（V2.0）：** 运行三套测试确认上述三项仍绿即可，无代码改动（见 V2.0）。

## Global Constraints

- 每个任务结束一次提交，前缀 `feat(v2.N):` / `fix(v2.N):`。
- 推送 `origin/main` 需用户显式确认（约定）；本地分支 `feat/v2-known-ceilings`。
- 不引入运行期新依赖。测试依赖仅限已在 `requirements.txt` 的（`pytest-asyncio==0.23.7` 已存在）。
- Spring Boot 3.3 / Java 17：使用 `jakarta.servlet.*`；Web 过滤器用 `jakarta.servlet.Filter`。
- 复用单一 `ApiKeyValidator` 供 Filter 与 WS Interceptor 共用，避免两套校验逻辑。
- 已知上限/简化用 `ponytail:` 注释标记（升级路径写在注释里）。
- 密钥默认 `changeme`，通过 `EG_API_KEY` 环境变量注入（docker `env_file: .env`）；生产必须改。

---

### Task V2.0: 收口确认（三套测试）

**Files:** 无改动。

- [ ] **Step 1: 后端测试**
Run: `cd eventguard-server && mvn -q test`
Expected: BUILD SUCCESS（含 `OrderCommandHandlerTest` 幂等用例）

- [ ] **Step 2: AI 测试**
Run: `cd eventguard-ai && python -m pytest tests -q`
Expected: 47 passed

- [ ] **Step 3: 前端测试**
Run: `cd eventguard-ui && npm run test -- --run`
Expected: 16 passed

- [ ] **Step 4: 提交（仅文档/占位，可跳过）**
无代码改动则跳过提交；本任务仅作为 V2 基线确认。

---

### Task V2.1: 服务端 API Key 校验（Filter + Validator）

**Files:**
- Create: `eventguard-server/src/main/java/com/eventguard/common/security/ApiKeyValidator.java`
- Create: `eventguard-server/src/main/java/com/eventguard/common/security/ApiKeyAuthFilter.java`
- Test: `eventguard-server/src/test/java/com/eventguard/common/security/ApiKeyAuthFilterTest.java`

**Interfaces:**
- Produces: `ApiKeyValidator.isValid(String) -> boolean`（V2.2 的 WS Interceptor 复用）
- Produces: `ApiKeyAuthFilter`（Spring `@Component`，自动注册到 `/*`）

- [ ] **Step 1: 写失败测试**
```java
package com.eventguard.common.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyAuthFilterTest {

    private final ApiKeyValidator validator = new ApiKeyValidator("secret");
    private final ApiKeyAuthFilter filter = new ApiKeyAuthFilter(validator);

    @Test
    void rejects_missing_key_with_401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    @Test
    void passes_valid_key_and_invokes_chain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/orders");
        req.addHeader("X-API-Key", "secret");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest(), "链应被继续执行");
    }

    @Test
    void skips_health_endpoint_without_key() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertNotNull(chain.getRequest());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-server && mvn -q test -Dtest=ApiKeyAuthFilterTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 Validator 与 Filter**
```java
package com.eventguard.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 共享 API Key 校验：供 REST 过滤器与 WS 握手拦截器复用。 */
@Component
public class ApiKeyValidator {

    private final String expectedKey;

    public ApiKeyValidator(@Value("${EG_API_KEY:changeme}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    public boolean isValid(String provided) {
        // ponytail: 等值比较即可，MVP 不引入常量时间比较/多密钥轮换；升级路径=换 JWT/OPA
        return provided != null && provided.equals(expectedKey);
    }
}
```
```java
package com.eventguard.common.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 校验入站请求的 X-API-Key 头；缺失/不匹配返回 401。 */
@Component
@Order(1)
public class ApiKeyAuthFilter implements Filter {

    private final ApiKeyValidator validator;

    public ApiKeyAuthFilter(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    public void doFilter(jakarta.servlet.ServletRequest request,
                          jakarta.servlet.ServletResponse response,
                          FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getServletPath();
        if (path.startsWith("/actuator") || path.equals("/health")) {
            chain.doFilter(request, response);
            return;
        }
        if (!validator.isValid(req.getHeader("X-API-Key"))) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid X-API-Key");
            return;
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-server && mvn -q test -Dtest=ApiKeyAuthFilterTest`
Expected: 3 tests passed

- [ ] **Step 5: 提交**
```bash
git add eventguard-server/src/main/java/com/eventguard/common/security/ eventguard-server/src/test/java/com/eventguard/common/security/
git commit -m "feat(v2.1): 服务端 X-API-Key 过滤器 + 共享校验器"
```

---

### Task V2.2: WebSocket 握手校验（复用 Validator）

**Files:**
- Create: `eventguard-server/src/main/java/com/eventguard/common/security/ApiKeyHandshakeInterceptor.java`
- Modify: `eventguard-server/src/main/java/com/eventguard/common/websocket/AnomalyWebSocketConfig.java:20-23`
- Test: `eventguard-server/src/test/java/com/eventguard/common/security/ApiKeyHandshakeInterceptorTest.java`

**Interfaces:**
- Consumes: `ApiKeyValidator.isValid(String)`（V2.1 产出）
- Produces: `ApiKeyHandshakeInterceptor`（注册到 `/ws/anomalies`）

- [ ] **Step 1: 写失败测试**
```java
package com.eventguard.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyHandshakeInterceptorTest {

    private final ApiKeyValidator validator = new ApiKeyValidator("secret");
    private final ApiKeyHandshakeInterceptor interceptor = new ApiKeyHandshakeInterceptor(validator);

    @Test
    void rejects_when_query_param_missing() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        // 用匿名实现模拟请求：getURI 带上无 api_key 的查询
        ServerHttpRequest req = new org.springframework.http.server.ServletServerHttpRequest(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/ws/anomalies"));
        assertFalse(interceptor.beforeHandshake(req, null, null, attrs));
    }

    @Test
    void passes_when_query_param_valid() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        org.springframework.mock.web.MockHttpServletRequest servletReq =
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/ws/anomalies?api_key=secret");
        ServerHttpRequest req = new org.springframework.http.server.ServletServerHttpRequest(servletReq);
        assertTrue(interceptor.beforeHandshake(req, null, null, attrs));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-server && mvn -q test -Dtest=ApiKeyHandshakeInterceptorTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 Interceptor 并注册**
```java
package com.eventguard.common.security;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** WS 握手校验：浏览器无法在 WS 连接带自定义头，密钥经查询参数 api_key 传递。 */
public class ApiKeyHandshakeInterceptor implements HandshakeInterceptor {

    private final ApiKeyValidator validator;

    public ApiKeyHandshakeInterceptor(ApiKeyValidator validator) {
        this.validator = validator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    org.springframework.web.socket.WebSocketHandler wsHandler,
                                    Map<String, Object> attributes) {
        String key = request.getURI().getQuery() != null ? null : null;
        // 从查询参数解析 api_key
        String q = request.getURI().getQuery();
        String provided = null;
        if (q != null) {
            for (String kv : q.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && "api_key".equals(p[0])) {
                    provided = java.net.URLDecoder.decode(p[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return validator.isValid(provided);
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               org.springframework.web.socket.WebSocketHandler wsHandler, Exception ex) {
        // 无需处理
    }
}
```
修改 `AnomalyWebSocketConfig.registerWebSocketHandlers`：
```java
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ponytail: setAllowedOrigins("*") 仅限 MVP；生产需改具体前端域名
        registry.addHandler(handler, "/ws/anomalies")
                .setAllowedOrigins("*")
                .addInterceptors(new ApiKeyHandshakeInterceptor(validator));
    }
```
并在 `AnomalyWebSocketConfig` 构造函数注入 `ApiKeyValidator validator`：
```java
    private final AnomalyWebSocketHandler handler;
    private final ApiKeyValidator validator;

    public AnomalyWebSocketConfig(AnomalyWebSocketHandler handler, ApiKeyValidator validator) {
        this.handler = handler;
        this.validator = validator;
    }
```

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-server && mvn -q test -Dtest=ApiKeyHandshakeInterceptorTest`
Expected: 2 tests passed

- [ ] **Step 5: 提交**
```bash
git add eventguard-server/src/main/java/com/eventguard/common/security/ApiKeyHandshakeInterceptor.java eventguard-server/src/main/java/com/eventguard/common/websocket/AnomalyWebSocketConfig.java eventguard-server/src/test/java/com/eventguard/common/security/ApiKeyHandshakeInterceptorTest.java
git commit -m "feat(v2.2): WS 握手校验复用 ApiKeyValidator（api_key 查询参数）"
```

---

### Task V2.3: AI 服务 API Key 依赖（FastAPI Depends）

**Files:**
- Modify: `eventguard-ai/app/config.py:4-20`（新增 `api_key` 字段）
- Modify: `eventguard-ai/app/main.py:1-56`（新增依赖 + 应用到两个端点）
- Test: `eventguard-ai/tests/test_auth.py`

**Interfaces:**
- Consumes: `settings.api_key`（来自 `EG_API_KEY` 环境变量）
- Produces: `verify_api_key` 依赖，供 `/ai/query` 与 `/anomalies/{id}/analysis` 使用

- [ ] **Step 1: 写失败测试**
```python
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
    # 401 以外即视为通过鉴权（业务失败另算）；此处只验证鉴权层
    assert resp.status_code != 401
```

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-ai && python -m pytest tests/test_auth.py -q`
Expected: FAIL（依赖未加，当前返回 200/422 而非 401）

- [ ] **Step 3: 实现配置项与依赖**
`app/config.py` 在 `Settings` 内新增字段（保持 `env_prefix="EG_"`）：
```python
    api_key: str = "changeme"
```
`app/main.py` 顶部新增导入并在端点应用：
```python
from fastapi import Depends, Header, HTTPException


async def verify_api_key(x_api_key: str = Header(None)):
    """校验 X-API-Key；缺失或不符返回 401。"""
    if x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="Missing or invalid X-API-Key")
    return True


@app.post("/ai/query", response_model=QueryResult)
async def ai_query(req: NLQueryRequest, _: bool = Depends(verify_api_key)):
    engine = _get_nl_query_engine()
    return await engine.query(req.question)


@app.get("/anomalies/{anomaly_id}/analysis")
async def get_analysis(anomaly_id: str, _: bool = Depends(verify_api_key)):
    ...
```
注意：`get_analysis` 在 V2.7 改为 `async`；此处先加依赖，V2.7 再改 `async def`（两步合并亦可，但保持任务独立）。

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-ai && python -m pytest tests/test_auth.py -q`
Expected: 2 passed

- [ ] **Step 5: 提交**
```bash
git add eventguard-ai/app/config.py eventguard-ai/app/main.py eventguard-ai/tests/test_auth.py
git commit -m "feat(v2.3): AI 服务 X-API-Key 依赖鉴权"
```

---

### Task V2.4: 前端注入 API Key（http.ts + WS + 构建变量）

**Files:**
- Modify: `eventguard-ui/src/api/http.ts:1-18`（axios 默认头注入）
- Modify: `eventguard-ui/src/composables/useAnomalyWebSocket.ts:21-22`（WS URL 追加 `?api_key=`）
- Create/Modify: `eventguard-ui/.env.example`（新增 `VITE_API_KEY`）
- Modify: `eventguard-ui/Dockerfile`（新增构建参数透传 `VITE_API_KEY`）
- Test: `eventguard-ui/src/api/__tests__/http.test.ts`（验证默认头）

**Interfaces:**
- Produces: 所有 REST 请求带 `X-API-Key`；WS 连接带 `?api_key=`（与 V2.2 查询参数对应）

- [ ] **Step 1: 写失败测试**
```ts
import { http } from '../http'

describe('http client', () => {
  it('attaches X-API-Key header from env', () => {
    expect(http.defaults.headers.common['X-API-Key']).toBe(import.meta.env.VITE_API_KEY)
  })
})
```

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-ui && npm run test -- --run src/api/__tests__/http.test.ts`
Expected: FAIL（头未设置）

- [ ] **Step 3: 注入密钥**
`src/api/http.ts` 在 `http` 创建后追加：
```ts
const apiKey = import.meta.env.VITE_API_KEY
if (apiKey) {
  http.defaults.headers.common['X-API-Key'] = apiKey
}
```
`src/composables/useAnomalyWebSocket.ts` 修改 `wsUrl` 拼接：
```ts
const apiKey = import.meta.env.VITE_API_KEY
const wsUrl = url
  || `${wsProto}://${window.location.host}/ws/anomalies${apiKey ? `?api_key=${apiKey}` : ''}`
```
`eventguard-ui/.env.example` 新增：
```
VITE_API_KEY=changeme
```
`eventguard-ui/Dockerfile` 在 `npm run build` 前透传（示例）：
```dockerfile
ARG VITE_API_KEY=changeme
ENV VITE_API_KEY=$VITE_API_KEY
RUN npm run build
```

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-ui && npm run test -- --run src/api/__tests__/http.test.ts`
Expected: PASS（在 vitest 配置中需将 `VITE_API_KEY` 设为测试 env；于 `vitest.config.ts` 的 `test.env` 或 `.env.test` 提供 `VITE_API_KEY=changeme`）

- [ ] **Step 5: 提交**
```bash
git add eventguard-ui/src/api/http.ts eventguard-ui/src/composables/useAnomalyWebSocket.ts eventguard-ui/.env.example eventguard-ui/Dockerfile eventguard-ui/src/api/__tests__/http.test.ts
git commit -m "feat(v2.4): 前端注入 API Key（REST 头 + WS 查询参数）"
```

---

### Task V2.5: 网关与样例配置（nginx 转发头 + .env.example）

**Files:**
- Modify: `eventguard-ui/nginx.conf:13-48`（各 location 显式转发 `X-API-Key`）
- Modify: `.env.example`（新增 `EG_API_KEY=changeme`）

**Interfaces:**
- 无新代码接口；确保 nginx 把客户端 `X-API-Key` 透传给后端与 AI 服务

- [ ] **Step 1: 在 nginx 各 location 显式转发头**
对 `/ws`、`/anomalies/`、`/ai/`、`/orders`、`/compensations` 各 `location` 块追加：
```nginx
        proxy_set_header X-API-Key $http_api_key;
```
（`/ws` 块已有 Upgrade 头；`/orders` 与 `/compensations` 已在 V2.4 由前端带头，nginx 默认会透传原请求头，但显式声明避免被覆盖。）

- [ ] **Step 2: .env.example 补 EG_API_KEY**
在根 `.env.example` 末尾追加：
```
# V2 新增：所有 REST/WS 接口鉴权密钥（前端 VITE_API_KEY 需与之相同）
EG_API_KEY=changeme
```

- [ ] **Step 3: 本地 dry-run 校验 nginx 配置**
Run: `docker run --rm -v ${PWD}/eventguard-ui/nginx.conf:/etc/nginx/conf.d/default.conf:ro nginx:alpine nginx -t`
Expected: `nginx: configuration file /etc/nginx/conf.d/default.conf test is successful`

- [ ] **Step 4: 提交**
```bash
git add eventguard-ui/nginx.conf .env.example
git commit -m "feat(v2.5): nginx 显式转发 X-API-Key + .env.example 补 EG_API_KEY"
```

---

### Task V2.6: 补偿命令处理器改为 Spring Bean（去除 new）

**Files:**
- Modify: `eventguard-server/src/main/java/com/eventguard/command/handler/CompensationCommandHandler.java:20-27`（加 `@Component`，保留 `EventStore` 注入）
- Modify: `eventguard-server/src/main/java/com/eventguard/compensation/service/CompensationService.java:24-57`（注入 handler 替换 `new`）
- Test: `eventguard-server/src/test/java/com/eventguard/compensation/service/CompensationServiceTest.java`

**Interfaces:**
- Consumes: `EventStore`（Spring Bean，已在 `EventStoreJdbcImpl` 提供）
- Produces: `CompensationCommandHandler` 作为可注入 Bean；`CompensationService` 不再持有 `EventStore`

- [ ] **Step 1: 写失败测试**
```java
package com.eventguard.compensation.service;

import com.eventguard.command.handler.CompensationCommandHandler;
import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompensationServiceTest {

    @Mock
    private CompensationActionRegistry registry;

    @Mock
    private CompensationCommandHandler commandHandler;

    @InjectMocks
    private CompensationService service;

    @Test
    void execute_delegates_to_injected_command_handler() {
        UUID aggregateId = UUID.randomUUID();
        when(registry.isSupported("refund")).thenReturn(true);
        when(commandHandler.handle(any())).thenReturn(
                new com.eventguard.common.dto.CommandResult(true, 1, null));

        CompensationResult result = service.execute(new CompensationRequest("refund", aggregateId, null));

        assertTrue(result.isSuccess());
        verify(commandHandler, times(1)).handle(any());
    }

    @Test
    void execute_rejects_null_aggregate_id() {
        CompensationResult result = service.execute(new CompensationRequest("refund", null, null));
        assertFalse(result.isSuccess());
        verify(commandHandler, never()).handle(any());
    }

    @Test
    void execute_rejects_unknown_action() {
        when(registry.isSupported("evil")).thenReturn(false);
        CompensationResult result = service.execute(new CompensationRequest("evil", UUID.randomUUID(), null));
        assertFalse(result.isSuccess());
        verify(commandHandler, never()).handle(any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-server && mvn -q test -Dtest=CompensationServiceTest`
Expected: FAIL（`CompensationService` 构造函数不接受 `CompensationCommandHandler`）

- [ ] **Step 3: 改造为 Bean**
`CompensationCommandHandler.java`：
```java
@Component
public class CompensationCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CompensationCommandHandler.class);
    private final EventStore eventStore;

    public CompensationCommandHandler(EventStore eventStore) {
        this.eventStore = eventStore;
    }
    // handle(...) 不变
}
```
`CompensationService.java`：
```java
@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);
    private final CompensationActionRegistry registry;
    private final CompensationCommandHandler commandHandler;

    public CompensationService(CompensationActionRegistry registry, CompensationCommandHandler commandHandler) {
        this.registry = registry;
        this.commandHandler = commandHandler;
    }

    public CompensationResult execute(CompensationRequest request) {
        String actionType = request.getActionType();
        UUID aggregateId = request.getAggregateId();

        if (aggregateId == null) {
            log.warn("[补偿] 拒绝执行：aggregateId 为空");
            return CompensationResult.failure("aggregateId 必填");
        }
        if (!registry.isSupported(actionType)) {
            log.warn("[补偿] 拒绝执行：动作 {} 不在白名单", actionType);
            return CompensationResult.failure("动作 " + actionType + " 不在白名单");
        }

        CompensationCommand cmd = new CompensationCommand(
                UUID.randomUUID(), aggregateId, actionType, request.getParams());
        try {
            CommandResult result = commandHandler.handle(cmd);   // 复用注入的 Bean
            if (result.success()) {
                var action = registry.get(actionType);
                String detail = action != null ? action.execute(aggregateId, request.getParams()) : "补偿已执行";
                log.info("[补偿] 执行成功：{}", detail);
                return CompensationResult.success(detail + "（事件版本 " + result.version() + "）");
            } else {
                return CompensationResult.failure(result.error());
            }
        } catch (Exception e) {
            log.error("[补偿] 执行异常：{}", e.getMessage(), e);
            return CompensationResult.failure("补偿执行异常：" + e.getMessage());
        }
    }
}
```
（移除 `eventguard.event.store.EventStore` 的 import 与 `eventStore` 字段；`ponytail:` 注释保留说明补偿为人工触发。）

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-server && mvn -q test -Dtest=CompensationServiceTest`
Expected: 3 tests passed

- [ ] **Step 5: 提交**
```bash
git add eventguard-server/src/main/java/com/eventguard/command/handler/CompensationCommandHandler.java eventguard-server/src/main/java/com/eventguard/compensation/service/CompensationService.java eventguard-server/src/test/java/com/eventguard/compensation/service/CompensationServiceTest.java
git commit -m "feat(v2.6): 补偿命令处理器 Spring Bean 化（去除 new）"
```

---

### Task V2.7: AI 调用改异步（httpx.AsyncClient 全链路）

**Files:**
- Modify: `eventguard-ai/app/analyzer/llm_client.py:26-50`（`generate` → `async`）
- Modify: `eventguard-ai/app/query/backend_client.py:22-51`（`get_*` → `async`）
- Modify: `eventguard-ai/app/query/template_executor.py:49-66`（`execute_*` → `async`，`await` 后端）
- Modify: `eventguard-ai/app/query/nl_query_engine.py:31-71`（`query`/`_route_template`/`_generate_answer` → `async`）
- Modify: `eventguard-ai/app/analyzer/root_cause.py:33-63`（`analyze` → `async`，`await` LLM）
- Modify: `eventguard-ai/app/main.py:31-56`（端点 → `async def`，`await` 引擎/分析器）
- Modify tests: `eventguard-ai/tests/test_nl_query_engine.py`、`test_template_executor.py`、`test_root_cause.py`（转 `async` + `AsyncMock`）

**Interfaces:**
- Produces: `LLMClient.generate` / `BackendClient.get_*` / `TemplateExecutor.execute_*` / `NLQueryEngine.query` / `RootCauseAnalyzer.analyze` 全部 `async`
- 行为不变（URL/参数/返回结构一致），仅消除事件循环阻塞

- [ ] **Step 1: 写/改失败测试（以 nl_query_engine 为例，其余同模式）**
将 `test_nl_query_engine.py` 全部测试方法改为 `async def` 并 `@pytest.mark.asyncio`，mock 用 `AsyncMock`：
```python
from unittest.mock import AsyncMock, MagicMock
import pytest
from app.query.nl_query_engine import NLQueryEngine
from app.query.query_result import QueryResult


class TestNLQueryEngine:
    @pytest.mark.asyncio
    async def test_query_event_lookup_routes_to_event_lookup_template(self):
        mock_classifier = MagicMock()
        mock_classifier.classify.return_value = "event_lookup"
        mock_executor = AsyncMock()
        mock_executor.execute_event_lookup.return_value = {"orderId": "abc", "status": "PAID"}
        mock_llm = AsyncMock()
        mock_llm.generate.return_value = "订单 abc 当前状态为 PAID。"

        engine = NLQueryEngine(
            intent_classifier=mock_classifier,
            template_executor=mock_executor,
            llm_client=mock_llm,
        )
        result = await engine.query("订单 abc 当前状态是什么？")
        assert isinstance(result, QueryResult)
        assert result.intent == "event_lookup"
        mock_executor.execute_event_lookup.assert_called_once()
        assert "PAID" in result.answer
```
（对 `test_template_executor.py`：`execute_*` 改为 `async`，`mock_backend.get_order = AsyncMock(...)`，调用处 `await executor.execute_event_lookup(...)`。`test_root_cause.py`：`analyze` 改 `async`，`mock_llm.generate = AsyncMock(...)`，`await analyzer.analyze(...)`。）

- [ ] **Step 2: 运行测试确认失败**
Run: `cd eventguard-ai && python -m pytest tests/test_nl_query_engine.py tests/test_template_executor.py tests/test_root_cause.py -q`
Expected: FAIL（`query`/`analyze` 仍为 sync，调用未 await）

- [ ] **Step 3: 实现异步（按文件改签名，逻辑不变）**
`llm_client.py`：
```python
    async def generate(self, prompt: str) -> str:
        url = f"{self.base_url}/chat/completions"
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {self.api_key}"}
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": "你是 EventGuard 电商订单异常根因分析助手。只输出 JSON。"},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
        }
        async with httpx.AsyncClient(timeout=30.0) as client:  # ponytail: 同 MVP 超时 30s 无重试
            resp = await client.post(url, headers=headers, json=body)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"]
```
`backend_client.py`：`self.client` 去除，每个方法 `async with httpx.AsyncClient(timeout=10.0) as client: resp = await client.get(...)`。
`template_executor.py`：`execute_event_lookup/execute_stats_aggregation/execute_trace_replay` 改为 `async def` 并对 `backend_client` 调用加 `await`。
`nl_query_engine.py`：`query`/`_route_template`/`_generate_answer` 改为 `async def`，`data = await self._route_template(...)`、`return (await self.llm_client.generate(prompt)).strip()`。
`root_cause.py`：`analyze` 改为 `async def`，`raw_response = await self.llm_client.generate(prompt)`。
`main.py`：`ai_query`/`get_analysis` 改为 `async def`，`return await engine.query(...)`、`report = await _analyzer.analyze(anomaly)`。

- [ ] **Step 4: 运行测试确认通过**
Run: `cd eventguard-ai && python -m pytest tests -q`
Expected: 47 passed（含改造后的同步测试全部转 async 通过）

- [ ] **Step 5: 提交**
```bash
git add eventguard-ai/app/analyzer/llm_client.py eventguard-ai/app/query/backend_client.py eventguard-ai/app/query/template_executor.py eventguard-ai/app/query/nl_query_engine.py eventguard-ai/app/analyzer/root_cause.py eventguard-ai/app/main.py eventguard-ai/tests/test_nl_query_engine.py eventguard-ai/tests/test_template_executor.py eventguard-ai/tests/test_root_cause.py
git commit -m "feat(v2.7): AI LLM/后端调用全链路改异步（httpx.AsyncClient）"
```

---

### Task V2.8: 未使用 import / 空白清理

**Files:** 仅清理 V2 改动触及文件中产生的未使用 import（扫描确认，无功能改动）

**Interfaces:** 无新增接口

- [ ] **Step 1: 后端编译 + 检查**
Run: `cd eventguard-server && mvn -q compile`
Expected: BUILD SUCCESS（编译器对未使用 import 仅警告不失败；人工复核警告）

- [ ] **Step 2: Python 导入自检**
Run: `cd eventguard-ai && python -c "import compileall,sys; sys.exit(0 if compileall.compile_dir('app', quiet=1) else 1)"`
Expected: 退出码 0

- [ ] **Step 3: 前端构建自检**
Run: `cd eventguard-ui && npm run build`
Expected: build 成功

- [ ] **Step 4: 人工复查 V2 触及文件的 import 是否仍被引用**
对以下文件用 grep 核对每个 import 名确有使用，删除未使用者（如 V2.6 后 `CompensationService` 中的 `EventStore` import）：
- `eventguard-server/.../compensation/service/CompensationService.java`
- `eventguard-server/.../common/security/ApiKeyAuthFilter.java`
- `eventguard-ai/app/main.py`、`eventguard-ai/app/query/backend_client.py`、`eventguard-ai/app/query/nl_query_engine.py`

- [ ] **Step 5: 提交（若有删除）**
```bash
git add -u
git commit -m "fix(v2.8): 清理 V2 改动引入的未使用 import"
```
若无改动则跳过提交。

---

### Task V2.9: Git LFS 瘦身（normal_events.jsonl / isolation_forest.pkl）

**Files:** `.gitattributes`（新增 LFS 追踪）；历史重写需 `git lfs migrate`

**Interfaces:** 无代码接口；仓库体积优化

> **警告（ponytail: 已知上限）**：`git lfs migrate import --everything` 会**重写 git 历史**，之后必须 force push `origin/main`。公开仓库强推有协作风险，执行前需用户显式确认并知会协作者。若不想重写历史，可只做前向追踪（步骤 1-2），接受历史中已存在的旧 blob。

- [ ] **Step 1: 安装并初始化 LFS**
Run: `git lfs install`
Expected: 输出 `Updated git hooks.` 或已初始化

- [ ] **Step 2: 前向追踪大文件（不重写历史，安全）**
Run:
```bash
git lfs track '*.jsonl' '*.pkl'
git add .gitattributes
git commit -m "build(v2.9): 前向追踪大文件到 Git LFS"
```
Expected: `.gitattributes` 含 `*.jsonl filter=lfs` 等；后续提交的大文件走 LFS

- [ ] **Step 3: （可选，需用户确认）历史重写瘦身**
Run（确认后再执行）：
```bash
git lfs migrate import --include='*.jsonl,*.pkl' --everything
git reflog expire --expire=now --all && git gc --prune=now
```
Expected: 历史中对应 blob 替换为 LFS 指针

- [ ] **Step 4: （仅当执行了步骤 3）强制推送**
Run（需用户确认）：`git push origin main --force`
Expected: 远端历史被 LFS 化版本覆盖；随后所有协作者需重新 clone

---

## Self-Review（规划期自检）

1. **Spec coverage**：原 8 项上限已全部覆盖——#1 鉴权（V2.1–V2.5）、#2/#3/#4 已确认完成（V2.0 收口）、#5 补偿 Bean（V2.6）、#6 AI 异步（V2.7）、#7 清理（V2.8）、#8 LFS（V2.9）。无遗漏。
2. **Placeholder scan**：无 TBD/TODO；每步含可运行命令与预期；V2.8 的“人工复查”为明确可执行的 grep 核对，非占位。
3. **Type consistency**：`ApiKeyValidator.isValid(String)` 在 V2.1 定义、V2.2 复用一致；`CompensationCommandHandler.handle(CompensationCommand)` 签名在 V2.6 前后不变；AI 异步链 `generate`/`get_*`/`execute_*`/`query`/`analyze` 在 V2.7 内统一改为 `async` 且返回类型不变。WS 密钥参数名 `api_key` 在 V2.2（解析）、V2.4（拼接）、V2.5（转发）三处一致。
4. **依赖**：V2.7 使用的 `pytest-asyncio==0.23.7` 已在 `requirements.txt`；其余零新依赖。
