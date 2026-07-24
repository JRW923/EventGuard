package com.eventguard.query.model;

import java.util.List;

/**
 * 订单列表分页响应。
 */
public class OrderListResponse {

    private List<OrderListItem> orders;
    private long total;
    private int page;
    private int size;

    public OrderListResponse() {}

    public OrderListResponse(List<OrderListItem> orders, long total, int page, int size) {
        this.orders = orders;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<OrderListItem> getOrders() { return orders; }
    public void setOrders(List<OrderListItem> orders) { this.orders = orders; }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}
