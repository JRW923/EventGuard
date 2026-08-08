package com.eventguard.gateway.http;

import com.eventguard.gateway.InventoryGateway;
import com.eventguard.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 库存服务网关（EG_INVENTORY_PROVIDER=http）：调用外部库存服务 REST API。
 * <p>
 * D 步「HTTP 适配器示例」。约定端点：
 * <ul>
 *   <li>GET  {base}/stock/{sku} → {"sku":"SKU-A","stock":100}</li>
 *   <li>POST {base}/reserve     body {"commandId","sku","quantity"} → 200/409（库存不足）</li>
 *   <li>POST {base}/release     body {"commandId","sku","quantity"}</li>
 * </ul>
 * 未配置 {@code EG_INVENTORY_SERVICE_URL} 时返回失败（不抛异常）。
 */
@Component
@ConditionalOnProperty(name = "eg.inventory.provider", havingValue = "http")
public class HttpInventoryGateway implements InventoryGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpInventoryGateway.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpInventoryGateway(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public ReservationResult reserve(ReserveRequest req) {
        if (properties.getInventoryServiceUrl().isBlank()) {
            return new ReservationResult(false, 0, "未配置 EG_INVENTORY_SERVICE_URL");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "commandId", req.commandId().toString(),
                    "sku", req.skuId(),
                    "quantity", req.quantity()));
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .timeout(REQUEST_TIMEOUT)
                            .uri(URI.create(properties.getInventoryServiceUrl() + "/reserve"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(resp.body());
                int remaining = node.path("remaining").asInt();
                return new ReservationResult(true, remaining, null);
            }
            return new ReservationResult(false, 0, "库存服务返回 " + resp.statusCode() + ": " + abbreviate(resp.body()));
        } catch (Exception e) {
            log.warn("[库存-http] 预留失败: {}", e.getMessage());
            return new ReservationResult(false, 0, e.getMessage());
        }
    }

    @Override
    public ReleaseResult release(ReleaseRequest req) {
        if (properties.getInventoryServiceUrl().isBlank()) {
            return new ReleaseResult(false, "未配置 EG_INVENTORY_SERVICE_URL");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "commandId", req.commandId().toString(),
                    "sku", req.skuId(),
                    "quantity", req.quantity()));
            httpClient.send(
                    HttpRequest.newBuilder()
                            .timeout(REQUEST_TIMEOUT)
                            .uri(URI.create(properties.getInventoryServiceUrl() + "/release"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new ReleaseResult(true, null);
        } catch (Exception e) {
            return new ReleaseResult(false, e.getMessage());
        }
    }

    @Override
    public int currentStock(String skuId) {
        if (properties.getInventoryServiceUrl().isBlank()) {
            return 0;
        }
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .timeout(REQUEST_TIMEOUT)
                            .uri(URI.create(properties.getInventoryServiceUrl() + "/stock/" + skuId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return objectMapper.readTree(resp.body()).path("stock").asInt(0);
            }
            return 0;
        } catch (Exception e) {
            log.warn("[库存-http] 查询库存失败 sku={}: {}", skuId, e.getMessage());
            return 0;
        }
    }

    @Override
    public MarkOutOfStockResult markOutOfStock(String skuId) {
        if (properties.getInventoryServiceUrl().isBlank()) {
            return new MarkOutOfStockResult(false, "未配置 EG_INVENTORY_SERVICE_URL");
        }
        try {
            httpClient.send(
                    HttpRequest.newBuilder()
                            .timeout(REQUEST_TIMEOUT)
                            .uri(URI.create(properties.getInventoryServiceUrl() + "/out-of-stock/" + skuId))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return new MarkOutOfStockResult(true, null);
        } catch (Exception e) {
            return new MarkOutOfStockResult(false, e.getMessage());
        }
    }

    private String abbreviate(String s) {
        return s == null ? "null" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }
}
