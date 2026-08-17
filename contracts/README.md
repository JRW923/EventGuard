# 跨栈事件契约

Java 写事件（`domain_events` 表，经 Debezium CDC 流入 Kafka `domain-events` topic）与 AI 读事件（`app/kafka_consumer.py` 展平消费）之间的契约。

## 契约要点（Java 侧必须产出、AI 侧必须能解析）

- 消息外层为 Debezium envelope：`{"schema": ..., "payload": {<行数据>}}`，`event_id` 位于 `payload` 内。
- `payload` 行数据含稳定标识：`event_id` / `aggregate_id` / `event_type`（如 `OrderCreatedEvent`）。
- Postgres `JSONB` 列在 CDC 中以**字符串**形式出现，`payload` / `metadata` 需解串为对象后再交给检测器。
- 业务字段位于 `payload` 列解串后的对象内：`orderId` / `userId` / `totalAmount` 等。

## 单一事实源

`domain-event.sample.json` 是本契约的样例消息（即 Java CDC 真实产出的形状）。

## 门禁

- **AI 侧**：`eventguard-ai/tests/test_event_contract.py` —— 用真实 `flatten_debezium_event` 处理样例 envelope，断言能取到各字段。
- **Java 侧**：`eventguard-server/.../event/store/DomainEventContractTest.java` —— 断言 `OrderCreatedEvent` 序列化出的 `payload` 含 `orderId/userId/totalAmount`、`event_type` 为 `OrderCreatedEvent`。

任一侧改动事件结构（改名/改 envelope/改 JSONB 序列化），对应测试会失败，防止跨栈漂移（历史上此处出过 envelope 未拆、字段名漂移导致的 500）。
