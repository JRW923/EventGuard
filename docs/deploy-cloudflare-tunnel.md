# 生产访问：Cloudflare Tunnel 免备案 HTTPS（不迁服务器）

## 背景与约束

- 域名 `jrwdev.site` / `www.jrwdev.site` 在腾讯云购买，但**未做 ICP 备案**。
- 源站服务器在腾讯云广州（公网 IP `111.231.101.27`），**不想迁移到境外**。
- 目标：用自定义域名 + HTTPS 访问 EventGuard，且不触发备案拦截。

## 为什么常规方案不行

### 1. 直接 A 记录 + Cloudflare 橙色云代理 —— 不行

未备案域名指向大陆服务器时，腾讯在**网络层对入站连接的 Host 头**做校验：Host 不是已备案域名就 302 跳到
`dnspod.qcloud.com/webblock.html`。

即使开 Cloudflare 橙色云，Cloudflare 回源到腾讯 IP 时 `Host` 仍是 `jrwdev.site`（未备案）→ 同样被 302 拦截。
**橙色云代理本身绕不开备案。**

### 2. Cloudflare Worker 反代 —— 不行

Worker 里 `fetch('http://111.231.101.27')` 会被拒绝：`error 1003 "Direct IP access is not allowed"`
（Cloudflare 禁止对 IP 字面量发起 fetch）；且 Worker 无法干净地代理 WebSocket。

## 解决方案：Cloudflare Tunnel（cloudflared）

核心思路：**让服务器主动向外连 Cloudflare**（出站 QUIC），而不是外部连入腾讯 IP。
这样入站连接永远不带「未备案 Host」，腾讯的 Host 校验无从触发。

```
浏览器 → Cloudflare 边缘（有效证书，HTTPS） → 隧道（服务器出站，QUIC） → eventguard-ui:80
```

- WebSocket / TLS 由 Cloudflare 透明处理，源站无需证书。
- 免费，且**无需绑定信用卡**：用 CLI 创建隧道，不走 Zero Trust 控制台。
- 隧道是长连接，断线由 `restart: unless-stopped` 自愈。

## 部署步骤

### 1. 安装 cloudflared 并登录

在服务器上安装 `cloudflared`，然后登录 Cloudflare 账号（会打开浏览器授权，授权目标 zone）：

```bash
cloudflared tunnel login
```

### 2. 用 CLI 创建隧道（无需 Zero Trust / 绑卡）

```bash
cloudflared tunnel create eventguard
# 控制台打印 tunnel ID，并在 ~/.cloudflared/<tunnel-id>.json 生成凭据
# 取出给 docker 容器用的长效 JWT 令牌：
cloudflared tunnel token eventguard
```

把输出写入 `.env`：

```
TUNNEL_TOKEN=eyJ...
```

> `.env` 已在 `.gitignore` 中，令牌不会被提交。其他成员复制 `.env.example` 自行填（见该文件 `TUNNEL_TOKEN` 注释）。

### 3. 绑定域名 DNS（必须删掉旧 A 记录）

Cloudflare 不允许同一主机名同时有 A 和 CNAME。若之前为 `jrwdev.site` 建过指向腾讯 IP 的 A 记录，
先在控制台**删除**该 A 记录（仅关橙色云不够，仍会回源到 IP 被拦），再：

```bash
cloudflared tunnel route dns eventguard jrwdev.site
cloudflared tunnel route dns eventguard www.jrwdev.site
```

这会在 Cloudflare 创建指向隧道的 CNAME（apex 用 CNAME 扁平化）。`route dns` 只建 DNS，转发目标由 `--url` 提供。

### 4. 启动隧道服务

`docker-compose.yml` 已含 `cloudflared` 服务（`restart: unless-stopped`）：

```yaml
cloudflared:
  image: cloudflare/cloudflared:latest
  command: tunnel run --token ${TUNNEL_TOKEN} --url http://eventguard-ui:80
  env_file: .env
  restart: unless-stopped
  depends_on: [eventguard-ui]
```

`--url` 把隧道流量转发到 docker 内部 `eventguard-ui:80`（UI 的 nginx）。改完配置：

```bash
docker compose up -d --build cloudflared
```

> ⚠️ **Worker Route 优先级高于隧道路由**：若之前建过 Worker Route，必须**删除**，否则请求被 Worker 拦截返回 1003。

## 验证

```bash
# 两个域名都应 200（Cloudflare 证书）
curl -I https://jrwdev.site/
curl -I https://www.jrwdev.site/

# WebSocket 升级（真实端点 /ws/anomalies，需 api_key）
curl -i -N -H "X-API-Key: root" \
  "https://jrwdev.site/ws/anomalies?api_key=root"
# 应看到：HTTP/1.1 101 Switching Protocols
```

DNS 校验（解析应到 Cloudflare 边缘 IP，而非源站 IP）：

```bash
dig @1.1.1.1 jrwdev.site +short   # → CNAME 到隧道；A 为 Cloudflare 边缘 IP（如 104.21.x / 172.67.x）
```

隧道到源站链路可用临时容器确认：`docker run --rm --network eventguard_default curlimages/curl -I http://eventguard-ui:80` 应 200。

## 缓存规则（Cloudflare API）

动态接口与 WebSocket 走 bypass，带哈希的 `/assets` 长缓存。Free 套餐**不支持**正则 `matches`，
用 `contains`：

| 命中路径 | 动作 | 说明 |
| --- | --- | --- |
| `/ws` `/orders` `/ai` `/anomalies` `/compensations` | Bypass cache | 动态 API / WS，不缓存 |
| `/assets/` | Cache | 带哈希静态资源；源站 nginx 已下发 `Cache-Control: public, max-age=31536000, immutable` |

通过 Rulesets API（`phase=http_request_cache_settings`，action `set_cache_settings`）推送。
最小权限 token：`Zone → Cache Rules → Edit` + `Zone → Zone → Read`，作用域限定 `jrwdev.site`。
推完即存于 zone，与隧道令牌无关；用毕在控制台 **Delete/Roll** 该 token。

## 已知上限（ponytail）

- 隧道依赖 Cloudflare 边缘可达；若 Cloudflare 被墙，访问随之中断（无自建兜底）。
- 源站 80 直接对外仍会被备案拦截，故**只**经隧道暴露，不要在防火墙另开 80 给公网直连。
- SSL/TLS 模式选 **Flexible** 即可：Cloudflare→源站走隧道自身加密，Full/Strict 是针对回源直连（对隧道无意义）。
  （源站 nginx 预留了 `/.well-known/acme-challenge/` 与 `/var/www/letsencrypt` webroot，若未来备案后改用 DNS-01 给源站签发证书可复用。）
