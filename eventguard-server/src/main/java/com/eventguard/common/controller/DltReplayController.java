package com.eventguard.common.controller;

import com.eventguard.auth.security.RequirePermission;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DLT 重放入口：把 <topic>.DLT 的消息原样发回主 topic，交给消费链路重新处理
 * （消费端均有幂等表，重复投递安全）。
 *
 * ponytail: 重放不删 DLT 消息（Kafka 无事务删除），重复调用会重复投递——由幂等表挡住；
 * 无分区跳过（DLT 与主 topic 分区数一致），仅管理员手动触发，不做并发防护。
 */
@RestController
@RequestMapping("/admin/dlt")
public class DltReplayController {

    private static final Logger log = LoggerFactory.getLogger(DltReplayController.class);

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final KafkaTemplate<String, String> dltKafkaTemplate;

    public DltReplayController(ConsumerFactory<Object, Object> consumerFactory,
                               KafkaTemplate<String, String> dltKafkaTemplate) {
        this.consumerFactory = consumerFactory;
        this.dltKafkaTemplate = dltKafkaTemplate;
    }

    @PostMapping("/{topic}/replay")
    @RequirePermission("user:manage")
    public Map<String, Object> replay(@PathVariable String topic) {
        String dltTopic = topic + ".DLT";
        Map<String, Object> result = new HashMap<>();
        result.put("dltTopic", dltTopic);
        try (Consumer<Object, Object> consumer = consumerFactory.createConsumer("dlt-replay", "replay")) {
            List<PartitionInfo> partitions = consumer.partitionsFor(dltTopic);
            if (partitions == null || partitions.isEmpty()) {
                result.put("replayed", 0);
                result.put("message", "DLT topic 不存在或无分区");
                return result;
            }
            List<TopicPartition> assignment = partitions.stream()
                    .map(p -> new TopicPartition(dltTopic, p.partition()))
                    .toList();
            consumer.assign(assignment);
            consumer.seekToBeginning(assignment);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assignment);
            long remaining = endOffsets.values().stream()
                    .reduce(0L, (a, b) -> a + b)
                    - assignment.stream().mapToLong(tp -> consumer.position(tp)).sum();

            int replayed = 0;
            while (replayed < remaining) {
                ConsumerRecords<Object, Object> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) break;
                for (ConsumerRecord<Object, Object> r : records) {
                    // 全局消费链路为 StringDeserializer，value 运行时即原始 JSON 字符串
                    dltKafkaTemplate.send(topic, r.partition(), (String) r.key(), (String) r.value());
                    replayed++;
                }
            }
            dltKafkaTemplate.flush();
            result.put("replayed", replayed);
            log.info("[DLT] 重放 {} 条消息回 {}", replayed, topic);
        }
        return result;
    }
}
