#!/usr/bin/env python3
"""将指定 Kafka 消费组的 offset 重置到 latest，使消费者跳过恢复数据库时产生的 CDC 事件。

原因：restore_demo.sh 用 pg_dump 覆盖数据库会触发 Debezium 重新捕获变更并写入
domain-events / anomaly-alerts topic。若不跳过，消费者重启后会"重放"这些恢复事件，
导致重复告警 / 重复触发补偿 Saga。把 offset 推到 latest 即可让消费者只读恢复之后
产生的新事件，历史状态以恢复后的数据库为准。

注意：必须在消费容器（eventguard-ai / eventguard-server）停止后、重启前执行。
"""
from kafka import KafkaConsumer

# 消费组 -> 订阅的 topic
GROUPS = {
    "ai-event-detector": ["domain-events"],
    "order-view-projection": ["domain-events"],
    "saga-trigger": ["domain-events"],
    "anomaly-ws": ["anomaly-alerts"],
}
BOOTSTRAP = "kafka:9092"


def reset_group(group: str, topics: list) -> None:
    consumer = KafkaConsumer(
        group_id=group,
        bootstrap_servers=BOOTSTRAP,
        auto_offset_reset="latest",
        enable_auto_commit=False,
        max_poll_records=1,
    )
    consumer.subscribe(topics)
    # poll 一次以触发分区分配
    consumer.poll(timeout_ms=8000)
    assigned = consumer.assignment()
    if not assigned:
        print(f"  {group}: 未分配到分区（topic 可能暂无数据），跳过")
        consumer.close()
        return
    consumer.seek_to_end()
    consumer.commit()
    print(f"  {group}: 已重置到 latest，分区数={len(assigned)}")
    consumer.close()


if __name__ == "__main__":
    print("[*] 重置消费组 offset 到 latest ...")
    for group, topics in GROUPS.items():
        reset_group(group, topics)
    print("[+] 完成")
