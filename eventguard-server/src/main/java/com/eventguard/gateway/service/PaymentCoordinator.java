package com.eventguard.gateway.service;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.aggregate.OrderAggregate;
import com.eventguard.gateway.PaymentGateway;
import com.eventguard.gateway.config.GatewayProperties;
import com.eventguard.gateway.model.GatewayRequest;
import com.eventguard.gateway.repository.GatewayRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 支付协调器：PayOrderCommand 提交后（Pay 命令已写 PaymentRequestedEvent）调用。
 * 写 gateway_request(PENDING) → 调 PaymentGateway.createPayment → 异步回调（mock 延时）
 * → GatewayCallbackService 按结果派发 CompletePaymentCommand / FailPaymentCommand。
 * <p>
 * ponytail: 单实例内存 ScheduledExecutor 延时模拟网关异步；真实网关（D 步）回调来自外部 HTTP，
 * 本协调器仅负责发起与落库，回调由 /gateway/callback/{provider} 进入。
 */
@Service
public class PaymentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PaymentCoordinator.class);

    private final PaymentGateway paymentGateway;
    private final GatewayRequestRepository gatewayRequestRepository;
    private final GatewayCallbackService callbackService;
    private final AggregateRepository aggregateRepository;
    private final GatewayProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "mock-gateway-callback"); t.setDaemon(true); return t; });

    public PaymentCoordinator(PaymentGateway paymentGateway,
                              GatewayRequestRepository gatewayRequestRepository,
                              GatewayCallbackService callbackService,
                              AggregateRepository aggregateRepository,
                              GatewayProperties properties) {
        this.paymentGateway = paymentGateway;
        this.gatewayRequestRepository = gatewayRequestRepository;
        this.callbackService = callbackService;
        this.aggregateRepository = aggregateRepository;
        this.properties = properties;
    }

    /**
     * 发起支付：从聚合读金额 → 建 gateway_request(PENDING) → 调网关。
     *
     * @return 发起结果：支付单号（成功时）；失败时 reason 非空
     */
    public InitiationResult initiate(UUID orderId, UUID commandId) {
        // 幂等：同一命令已发起过则直接返回，避免重复建网关单
        java.util.Optional<GatewayRequest> existing = gatewayRequestRepository.findByCommandId(commandId);
        if (existing.isPresent()) {
            GatewayRequest req = existing.get();
            return new InitiationResult(req.getExternalRef(),
                    req.getStatus() == GatewayRequest.Status.FAILED, null);
        }

        OrderAggregate order = aggregateRepository.load(orderId);
        BigDecimal amount = order.getTotalAmount();

        PaymentGateway.CreatePaymentResult result = paymentGateway.createPayment(
                new PaymentGateway.CreatePaymentRequest(orderId, commandId, amount));

        GatewayRequest req = new GatewayRequest(
                UUID.randomUUID(), commandId, orderId, "PAYMENT", "CREATE_PAYMENT",
                properties.getPaymentProvider(), result.paymentId(),
                result.success() ? GatewayRequest.Status.PENDING : GatewayRequest.Status.FAILED,
                Map.of("amount", amount == null ? "0" : amount.toString()), Map.of(), Instant.now(), Instant.now());
        gatewayRequestRepository.insert(req);

        if (!result.success()) {
            // 网关创建即失败（如失败率注入）：直接走失败回调，订单 → PAYMENT_FAILED
            log.warn("[支付] 网关创建支付失败 order={} reason={}", orderId, result.error());
            callbackService.process(result.paymentId(), orderId, false, result.error());
            return new InitiationResult(null, true, result.error());
        }

        // mock 异步：延时回调成功；真实网关由外部回调进入
        long delayMs = properties.getPaymentDelayMs();
        if (delayMs > 0) {
            scheduler.schedule(() -> callbackService.process(result.paymentId(), orderId, true, null),
                    delayMs, TimeUnit.MILLISECONDS);
            log.info("[支付] 已发起（异步待回调）order={} paymentId={} delay={}ms", orderId, result.paymentId(), delayMs);
        } else {
            // 无延迟：立即回调（同步完成，保持 demo 可即时看到 PAID）
            callbackService.process(result.paymentId(), orderId, true, null);
        }
        return new InitiationResult(result.paymentId(), false, null);
    }

    /** 发起结果。 */
    public record InitiationResult(String paymentId, boolean failed, String reason) {}
}
