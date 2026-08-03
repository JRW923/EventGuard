"""Postgres 访问：读 domain_events / order_view / command_log / notification_log / compensation_approval / gateway_request。

append_event 例外：评测器把 kafka_inject 合成事件也追加进 append-only 事件表（带 ON CONFLICT 幂等），
保持 DB 与 AI 检测所见的 Kafka 事件流一致；其余均为只读查询。
"""
from __future__ import annotations

import json
from typing import Any

import psycopg2
import psycopg2.extras


class Db:
    def __init__(self, cfg) -> None:
        self.cfg = cfg
        self.conn = None

    def connect(self) -> "Db":
        self.conn = psycopg2.connect(self.cfg.pg_dsn)
        return self

    def query(self, sql: str, params: tuple | None = None) -> list[dict]:
        with self.conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params or ())
            return list(cur.fetchall())

    def scalar(self, sql: str, params: tuple | None = None) -> Any:
        rows = self.query(sql, params)
        if not rows:
            return None
        return next(iter(rows[0].values()))

    # —— 常用只读查询 ——
    def event_count(self, aggregate_id: str) -> int:
        return int(
            self.scalar(
                "SELECT count(*) FROM domain_events WHERE aggregate_id = %s", (aggregate_id,)
            ) or 0
        )

    def events(self, aggregate_id: str) -> list[dict]:
        return self.query(
            "SELECT event_id, event_type, event_version, created_at, payload, metadata "
            "FROM domain_events WHERE aggregate_id = %s ORDER BY event_version",
            (aggregate_id,),
        )

    def order_view(self, aggregate_id: str) -> dict | None:
        rows = self.query("SELECT * FROM order_view WHERE order_id = %s", (aggregate_id,))
        return rows[0] if rows else None

    def command_log_count(self, command_id: str) -> int:
        return int(
            self.scalar("SELECT count(*) FROM command_log WHERE command_id = %s", (command_id,)) or 0
        )

    def notification_log(self, aggregate_id: str) -> list[dict]:
        return self.query(
            "SELECT * FROM notification_log WHERE aggregate_id = %s ORDER BY created_at",
            (aggregate_id,),
        )

    def approval_rows(self, aggregate_id: str) -> list[dict]:
        return self.query(
            "SELECT * FROM compensation_approval WHERE aggregate_id = %s ORDER BY requested_at",
            (aggregate_id,),
        )

    def gateway_requests(self, aggregate_id: str) -> list[dict]:
        return self.query(
            "SELECT * FROM gateway_request WHERE aggregate_id = %s ORDER BY created_at",
            (aggregate_id,),
        )

    def max_event_version(self, aggregate_id: str) -> int:
        return int(
            self.scalar(
                "SELECT COALESCE(MAX(event_version), 0) FROM domain_events WHERE aggregate_id = %s",
                (aggregate_id,),
            ) or 0
        )

    def append_event(self, event: dict) -> None:
        """kafka_inject：向 append-only 事件表追加合成事件（评测器专用，带 ON CONFLICT 幂等）。"""
        with self.conn.cursor() as cur:
            cur.execute(
                "INSERT INTO domain_events "
                "(event_id, aggregate_id, aggregate_type, event_type, event_version, payload, metadata, created_at) "
                "VALUES (%s,%s,%s,%s,%s,%s::jsonb,%s::jsonb,%s) ON CONFLICT DO NOTHING",
                (
                    event["event_id"],
                    event["aggregate_id"],
                    event.get("aggregate_type", "Order"),
                    event["event_type"],
                    event["event_version"],
                    json.dumps(event.get("payload", {}), ensure_ascii=False),
                    json.dumps(event.get("metadata", {}), ensure_ascii=False),
                    event["created_at"],
                ),
            )
            self.conn.commit()

    def close(self) -> None:
        if self.conn:
            self.conn.close()
