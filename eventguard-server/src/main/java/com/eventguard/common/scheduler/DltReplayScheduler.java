package com.eventguard.common.scheduler;

import com.eventguard.common.controller.DltReplayController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * DLT 定时自动重放：把 <topic>.DLT 中因瞬时故障（DB 争用/死锁）进死信的消息周期性重投回主 topic，
 * 由幂等投影链路重新处理，避免订单视图永久缺失最新状态（表现为读己写 409 的永久卡死子集）。
 *
 * ponytail: 直接复用 DltReplayController.replay（同为 public 方法，且 @RequirePermission 仅作用在 HTTP 层
 * HandlerInterceptor，调度线程内部调用不触发鉴权）；重放从 DLT 头全量重投，幂等表挡重复，安全可重复。
 * 默认只重放 domain-events.DLT（订单投影），多 topic 用 eg.dlt.replay-topics 配置。
 */
@Component
public class DltReplayScheduler {

    private static final Logger log = LoggerFactory.getLogger(DltReplayScheduler.class);

    private final DltReplayController replayController;
    private final List<String> topics;

    public DltReplayScheduler(DltReplayController replayController,
                              @Value("${eg.dlt.replay-topics:domain-events}") String topics) {
        this.replayController = replayController;
        this.topics = Arrays.stream(topics.split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).toList();
    }

    @Scheduled(cron = "${eg.dlt.replay-cron:0 0/10 * * * *}", zone = "Asia/Shanghai")
    public void replayAll() {
        for (String topic : topics) {
            try {
                var result = replayController.replay(topic);
                int replayed = (int) result.getOrDefault("replayed", 0);
                if (replayed > 0) {
                    log.info("[DLT 定时重放] {} 重投 {} 条回主 topic", topic, replayed);
                }
            } catch (Exception e) {
                log.error("[DLT 定时重放] {} 失败", topic, e);
            }
        }
    }
}
