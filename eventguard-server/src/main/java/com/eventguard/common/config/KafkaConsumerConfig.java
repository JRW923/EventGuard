package com.eventguard.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/** Kafka listener 错误策略：处理异常有限重试后进入 <topic>.DLT。注意：契约不兼容/无法解析的消息已在 EventDeserializer 降级为 UnknownEvent 并跳过，不会到达此处。 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * DLT 专用纯字符串模板：主消费链路是 StringDeserializer（Debezium 原样 JSON 字符串），
     * 若复用全局 JsonSerializer 模板，DLT 消息会被二次 JSON 编码并附加 __TypeId__ 头，
     * 人工重放回主 topic 时消费端无法解析。原样字符串让 DLT ↔ 主 topic 可无损往返。
     */
    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate(KafkaProperties properties) {
        @SuppressWarnings("deprecation")
        Map<String, Object> cfg = properties.buildProducerProperties();
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> dltKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                (KafkaOperations) dltKafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // 1 秒、2 次重试；恢复器发布成功后 Spring 才提交原 offset。
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    /** 暴露 kafka.consumer.* 指标（含 fetch.manager.records.lag 消费积压），供 Prometheus 告警。 */
    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public MicrometerConsumerListener<?, ?> micrometerConsumerListener(
            MeterRegistry registry, ObjectProvider<ConsumerFactory> consumerFactory) {
        MicrometerConsumerListener<?, ?> listener = new MicrometerConsumerListener<>(registry);
        consumerFactory.ifAvailable(f -> f.addListener(listener));
        return listener;
    }
}
