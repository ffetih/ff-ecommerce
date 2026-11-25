package io.github.ffetih.ecommerce.shipping.adapter.out.persistence;

import io.github.ffetih.ecommerce.shipping.application.port.CancelledOrders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
class JdbcCancelledOrders implements CancelledOrders {

    private final JdbcClient jdbc;

    JdbcCancelledOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID orderId, Instant cancelledAt) {
        jdbc.sql("INSERT INTO cancelled_orders (order_id, cancelled_at) VALUES (:orderId, :at) ON CONFLICT DO NOTHING")
                .param("orderId", orderId)
                .param("at", OffsetDateTime.ofInstant(cancelledAt, ZoneOffset.UTC))
                .update();
    }

    @Override
    public boolean contains(UUID orderId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM cancelled_orders WHERE order_id = :orderId)")
                .param("orderId", orderId)
                .query(Boolean.class)
                .single();
    }
}
