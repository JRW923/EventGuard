package com.eventguard.common.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 幂等消费表定期清理：idempotent_consumers 随事件量线性增长，不清理则无界。
 * 保留窗口 30 天：远大于 Kafka DLT 重放/重复投递可能触及的回看范围。
 *
 * ponytail: 单实例 @Scheduled，多副本会并发执行同一条 DELETE（幂等无害，无锁）；
 * 若引入更多清理任务再考虑分布式锁。
 */
@Component
public class IdempotentConsumerCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumerCleanup.class);

    private final JdbcTemplate jdbc;

    public IdempotentConsumerCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${eg.idempotent.cleanup-cron:0 17 3 * * *}", zone = "Asia/Shanghai")
    public void purgeExpired() {
        int deleted = jdbc.update(
                "DELETE FROM idempotent_consumers WHERE processed_at < now() - interval '30 days'");
        if (deleted > 0) {
            log.info("[幂等表] 清理 {} 条 30 天前的消费记录", deleted);
        }
    }
}
