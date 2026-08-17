package com.eventguard.common.controller;

import com.eventguard.auth.security.RequirePermission;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DLT 重放入口：把 <topic>.DLT 中尚未重放的消息发回主 topic，交给消费链路重新处理
 * （消费端均有幂等表，重复投递安全）。
 *
 * ponytail: 增量重放——从 dlt-replay 消费组已提交位移继续，不再每次 seekToBeginning 全量重放，
 * 因此不会每 10 分钟把整个 DLT 重复投递一遍（旧实现会无限循环重放、毒消息永久打转）。
 * 每条消息携带 eg.dlt.replay.attempt 头计数，达到上限改投 <topic>.DLT.quarantine 隔离，
 * 杜绝毒消息永久循环占用重放带宽。Kafka 无法事务删除 DLT，已读消息仅推进位移、留在 DLT 作审计日志。
 * 隔离 topic 依赖 broker auto.create.topics.enable=true 自动建（内部运维工具，可接受）。
 */
@RestController
@RequestMapping("/admin/dlt")
public class DltReplayController {

    private static final Logger log = LoggerFactory.getLogger(DltReplayController.class);
    private static final String ATTEMPT_HEADER = "eg.dlt.replay.attempt";
    private static final long REPLAY_TIME_BUDGET_MS = 30_000; // 单次重放最多 30s，防止卡死阻塞调度

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final KafkaTemplate<String, String> dltKafkaTemplate;
    private final int maxReplayAttempts;

    public DltReplayController(ConsumerFactory<Object, Object> consumerFactory,
                               KafkaTemplate<String, String> dltKafkaTemplate,
                               @Value("${eg.dlt.max-replay-attempts:3}") int maxReplayAttempts) {
        this.consumerFactory = consumerFactory;
        this.dltKafkaTemplate = dltKafkaTemplate;
        this.maxReplayAttempts = maxReplayAttempts;
    }

    @PostMapping("/{topic}/replay")
    @RequirePermission("user:manage")
    public Map<String, Object> replay(@PathVariable String topic) {
        String dltTopic = topic + ".DLT";
        String quarantineTopic = dltTopic + ".quarantine";
        Map<String, Object> result = new HashMap<>();
        result.put("dltTopic", dltTopic);
        result.put("quarantineTopic", quarantineTopic);
        result.put("maxReplayAttempts", maxReplayAttempts);
        try (Consumer<Object, Object> consumer = consumerFactory.createConsumer("dlt-replay", "replay")) {
            List<PartitionInfo> partitions = consumer.partitionsFor(dltTopic);
            if (partitions == null || partitions.isEmpty()) {
                result.put("replayed", 0);
                result.put("quarantined", 0);
                result.put("message", "DLT topic 不存在或无分区");
                return result;
            }
            List<TopicPartition> assignment = partitions.stream()
                    .map(p -> new TopicPartition(dltTopic, p.partition()))
                    .toList();
            consumer.assign(assignment);
            // 仅对“尚无已提交位移”的分区从开头读（首次/清组后处理积压一次）；其余从已提交位移继续（增量）
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(assignment));
            assignment.forEach(tp -> { if (committed.get(tp) == null) consumer.seekToBeginning(List.of(tp)); });

            int replayed = 0, quarantined = 0;
            long deadline = System.currentTimeMillis() + REPLAY_TIME_BUDGET_MS;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<Object, Object> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) break;
                for (ConsumerRecord<Object, Object> r : records) {
                    int attempt = readAttempt(r);
                    int next = attempt + 1;
                    Headers headers = copyHeaders(r.headers());
                    headers.remove(ATTEMPT_HEADER);
                    headers.add(ATTEMPT_HEADER, String.valueOf(next).getBytes(StandardCharsets.UTF_8));
                    if (next > maxReplayAttempts) {
                        // 超过重试上限：隔离到 quarantine，不再重放，杜绝毒消息永久循环
                        dltKafkaTemplate.send(new ProducerRecord<>(quarantineTopic, r.partition(),
                                (String) r.key(), (String) r.value(), headers));
                        quarantined++;
                    } else {
                        dltKafkaTemplate.send(new ProducerRecord<>(topic, r.partition(),
                                (String) r.key(), (String) r.value(), headers));
                        replayed++;
                    }
                }
            }
            dltKafkaTemplate.flush();
            consumer.commitSync();
            result.put("replayed", replayed);
            result.put("quarantined", quarantined);
            log.info("[DLT] 重放 {} 条 / 隔离 {} 条（上限 {}）from {}", replayed, quarantined, maxReplayAttempts, dltTopic);
        }
        return result;
    }

    private int readAttempt(ConsumerRecord<Object, Object> r) {
        Header h = r.headers().lastHeader(ATTEMPT_HEADER);
        if (h == null) return 0;
        try {
            return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Headers copyHeaders(Headers original) {
        RecordHeaders copy = new RecordHeaders();
        for (Header h : original) {
            copy.add(h);
        }
        return copy;
    }
}
