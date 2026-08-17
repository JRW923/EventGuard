# 恢复演练脚本（restore-drill.sh）

对当前生产库做一份即时备份 → 恢复到**独立演练库** `eventguard_drift`（不触碰生产数据）→ 校验核心表零丢失与读模型一致 → 清理演练库。

用途：关闭「备份只验过 `pg_restore --list`、从没在空库真恢复过」这个生产就绪缺口；周期性运行则把「补过一次」升级成「持续保证」。

## 用法

```bash
./restore-drill.sh                                                              # 默认参数运行
DRILL_DB=eventguard_drift2 BACKUP_DIR=/data/eg-backups ./restore-drill.sh       # 自定义演练库/备份目录
DRILL_KEEP_DUMP=1 ./restore-drill.sh                                            # 保留演练用 dump 作证据
```

环境变量：`PG_CONTAINER` / `PG_USER` / `PG_DB` / `DRILL_DB` / `BACKUP_DIR` / `DRILL_KEEP_DUMP`。

## 校验内容

- `domain_events` / `order_view` / `command_log` 行数与生产库基准一致（零丢失）
- 读模型一致性：`order_view` 行数 == `OrderCreatedEvent` 事件数（业务可用性代理）

任一项不符即以非零退出，可直接挂 cron / 监控告警。

## 周期性运行（cron）

```
27 4 * * 0 /opt/EventGuard/scripts/restore-drill.sh >> /var/log/eg-restore-drill.log 2>&1
```

## 资源影响（实测）

当前生产库约 **143 MB**，实跑约 **11 秒**。瞬时峰值磁盘约 **180 MB**（演练库整份副本 + dump 文件），跑完即释放，无永久占用。唯一实质成本是那 11 秒的磁盘 IO 突发，会与线上 Postgres / Debezium CDC 抢同一块盘——放低峰运行即可。成本随库大小**线性增长**：库到 GB 级时应降频或改为独立还原校验实例。

## 已知上限（ponytail）

- 恢复进**同主机同实例**的临时库，验证的是「恢复机制 + 数据完整性」，不覆盖**异地/跨机恢复**（见生产就绪缺口：异地备份项）。
- 校验是**数据层**（表行数 + 读模型一致），不是起一个 app 指向演练库打 HTTP 的真业务可用。
- 不保证真实崩溃零丢失：备份是时间点快照，备份之后的数据仍会丢（属 RPO，非本脚本职责）。
