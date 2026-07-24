package com.eventguard.anomaly.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 规则上下文：携带规则评估所需的聚合数据 */
public class RuleContext {

    private final BigDecimal userMeanAmount;
    private final BigDecimal userStdAmount;
    private final List<Instant> recentPaymentCompletions;
    private final String previousState;
    private final String currentState;
    private final List<Instant> recentCreateOrders;
    private final int actualStock;
    private final int reservedQty;

    private RuleContext(Builder b) {
        this.userMeanAmount = b.userMeanAmount;
        this.userStdAmount = b.userStdAmount;
        this.recentPaymentCompletions = b.recentPaymentCompletions;
        this.previousState = b.previousState;
        this.currentState = b.currentState;
        this.recentCreateOrders = b.recentCreateOrders;
        this.actualStock = b.actualStock;
        this.reservedQty = b.reservedQty;
    }

    public BigDecimal getUserMeanAmount() { return userMeanAmount; }
    public BigDecimal getUserStdAmount() { return userStdAmount; }
    public List<Instant> getRecentPaymentCompletions() { return recentPaymentCompletions; }
    public String getPreviousState() { return previousState; }
    public String getCurrentState() { return currentState; }
    public List<Instant> getRecentCreateOrders() { return recentCreateOrders; }
    public int getActualStock() { return actualStock; }
    public int getReservedQty() { return reservedQty; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private BigDecimal userMeanAmount = BigDecimal.ZERO;
        private BigDecimal userStdAmount = BigDecimal.ONE;
        private List<Instant> recentPaymentCompletions = List.of();
        private String previousState;
        private String currentState;
        private List<Instant> recentCreateOrders = List.of();
        private int actualStock = Integer.MAX_VALUE;
        private int reservedQty = 0;

        public Builder userMeanAmount(BigDecimal v) { this.userMeanAmount = v; return this; }
        public Builder userStdAmount(BigDecimal v) { this.userStdAmount = v; return this; }
        public Builder recentPaymentCompletions(List<Instant> v) { this.recentPaymentCompletions = v; return this; }
        public Builder previousState(String v) { this.previousState = v; return this; }
        public Builder currentState(String v) { this.currentState = v; return this; }
        public Builder recentCreateOrders(List<Instant> v) { this.recentCreateOrders = v; return this; }
        public Builder actualStock(int v) { this.actualStock = v; return this; }
        public Builder reservedQty(int v) { this.reservedQty = v; return this; }

        public RuleContext build() { return new RuleContext(this); }
    }
}
