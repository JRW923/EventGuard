package com.eventguard.common.controller;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DLT 重放端到端：毒消息 → 经生产同款 DefaultErrorHandler + DeadLetterPublishingRecoverer 进 <topic>.DLT
 * → 调用真实 DltReplayController.replay 把消息原样发回主 topic → 健康消费者成功处理（恢复）。
 *
 * 默认跳过（本地无 Docker / CI 需 eventguard.run.integration=true）；复用第 1 条 integration job 的基础设施。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "eventguard.run.integration", matches = "true")
class DltReplayIntegrationTest {

    private static final String TOPIC = "dlt-demo";
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    private static final String POISON = "POISON-" + System.nanoTime();

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    static KafkaTemplate<String, String> mainTemplate;
    static KafkaTemplate<String, String> dltTemplate;
    static ConsumerFactory<Object, Object> replayConsumerFactory;
    static DltReplayController controller;

    static ConcurrentMessageListenerContainer<String, String> poison;
    static ConcurrentMessageListenerContainer<String, String> healthy;

    static Map<String, Object> baseProps() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return p;
    }

    @BeforeAll
    static void setup() throws Exception {
        Map<String, Object> props = baseProps();
        mainTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        // 与生产 dltKafkaTemplate 一致：纯字符串模板，保证 DLT ↔ 主 topic 无损往返
        dltTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));

        Map<String, Object> consumerProps = baseProps();
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replay-assert");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        replayConsumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        controller = new DltReplayController(replayConsumerFactory, dltTemplate);

        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(
                    new NewTopic(TOPIC, 1, (short) 1),
                    new NewTopic(DLT_TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void teardown() {
        if (poison != null) poison.stop();
        if (healthy != null) healthy.stop();
        if (mainTemplate != null) mainTemplate.destroy();
        if (dltTemplate != null) dltTemplate.destroy();
    }

    /** 与生产 KafkaConsumerConfig.kafkaErrorHandler 同款路由：失败 → <topic>.DLT，同分区。 */
    private static CommonErrorHandler dltErrorHandler() {
        var recoverer = new DeadLetterPublishingRecoverer(
                dltTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    private static ConcurrentMessageListenerContainer<String, String> startContainer(
            String group, String reset, MessageListener<String, String> listener) {
        Map<String, Object> props = baseProps();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, reset);
        ContainerProperties cp = new ContainerProperties(TOPIC);
        cp.setMessageListener((Object) listener);
        var container = new ConcurrentMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, String>(props), cp);
        container.setCommonErrorHandler(dltErrorHandler());
        container.start();
        return container;
    }

    @Test
    void poison_message_lands_in_dlt_then_replay_recovers_it() throws Exception {
        var recovered = new ConcurrentLinkedQueue<String>();
        var latch = new CountDownLatch(1);

        // 阶段一：毒监听器（group=earliest）—— 遇 POISON 抛异常，经重试后落入 DLT
        poison = startContainer("poison-grp", "earliest", rec -> {
            if (rec.value().contains("POISON")) throw new RuntimeException("transient bug");
        });

        mainTemplate.send(TOPIC, POISON).get(10, TimeUnit.SECONDS);
        awaitDltCapture(POISON);                 // 断言 1：毒消息已进入 DLT
        poison.stop();

        // 阶段二：健康监听器（group=latest，仅看重放后的新流量）成功处理消息
        healthy = startContainer("healthy-grp", "latest", rec -> {
            recovered.add(rec.value());
            latch.countDown();
        });

        controller.replay(TOPIC);                // 真实重放：DLT 原样发回主 topic

        if (!latch.await(20, TimeUnit.SECONDS)) {
            fail("重放后健康消费者未在超时内收到消息，recovered=" + recovered);
        }
        assertThat(recovered).contains(POISON);  // 断言 2：重放链路恢复，消息被成功处理
    }

    private static void awaitDltCapture(String expected) {
        Map<String, Object> p = baseProps();
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-wait");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(DLT_TOPIC));
            for (int i = 0; i < 40; i++) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofSeconds(1))) {
                    if (expected.equals(r.value())) return;
                }
            }
        }
        fail("DLT 未捕获毒消息：" + expected);
    }
}
