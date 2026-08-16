# EventGuard 服务端

Spring Boot 订单命令端、查询端、投影和治理能力。

- `src/main/java/com/eventguard/domain`：订单聚合、事件和领域规则。
- `src/main/java/com/eventguard/command`：写入命令、幂等和并发控制。
- `src/main/java/com/eventguard/query`：读模型、投影和读己写查询。
- `src/main/java/com/eventguard/common`：鉴权、配置、异常和基础设施。
- `src/test`：单元测试与集成测试。

本地运行：`mvn spring-boot:run`。完整依赖栈使用根目录 Docker Compose 启动。
