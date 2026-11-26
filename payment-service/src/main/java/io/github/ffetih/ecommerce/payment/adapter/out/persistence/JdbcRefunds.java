package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.RefundRepository;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Refund;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcRefunds implements RefundRepository {

    private static final String COLUMNS = """
            id, payment_id, order_id, return_id, amount, currency, status, provider_reference, failure_reason,
            created_at, updated_at""";

    private final JdbcClient jdbc;

    JdbcRefunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Refund> findByReturnId(UUID returnId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE return_id = :returnId")
                .param("returnId", returnId)
                .query(JdbcRefunds::map)
                .optional();
    }

    @Override
    public Optional<Refund> lockById(UUID refundId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE id = :id FOR UPDATE")
                .param("id", refundId)
                .query(JdbcRefunds::map)
                .optional();
    }

    @Override
    public List<Refund> findByOrderId(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM refunds WHERE order_id = :orderId ORDER BY created_at")
                .param("orderId", orderId)
                .query(JdbcRefunds::map)
                .list();
    }

    @Override
    public List<Refund> findInFlight(Instant updatedBefore, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM refunds
                        WHERE status = 'REFUNDING' AND updated_at < :before
                        ORDER BY updated_at
                        LIMIT :limit""")
                .param("before", JdbcPayments.utc(updatedBefore))
                .param("limit", limit)
                .query(JdbcRefunds::map)
                .list();
    }

    @Override
    public void save(Refund refund) {
        jdbc.sql("""
                        INSERT INTO refunds (%s)
                        VALUES (:id, :paymentId, :orderId, :returnId, :amount, :currency, :status, :providerReference,
                                :failureReason, :createdAt, :updatedAt)
                        ON CONFLICT (id) DO UPDATE SET
                            status = EXCLUDED.status,
                            provider_reference = EXCLUDED.provider_reference,
                            failure_reason = EXCLUDED.failure_reason,
                            updated_at = EXCLUDED.updated_at""".formatted(COLUMNS))
                .param("id", refund.id())
                .param("paymentId", refund.paymentId())
                .param("orderId", refund.orderId())
                .param("returnId", refund.returnId())
                .param("amount", refund.amount().amount())
                .param("currency", refund.amount().currency().getCurrencyCode())
                .param("status", refund.status().name())
                .param("providerReference", refund.providerReference())
                .param("failureReason", refund.failureReason())
                .param("createdAt", JdbcPayments.utc(refund.createdAt()))
                .param("updatedAt", JdbcPayments.utc(refund.updatedAt()))
                .update();
    }

    private static Refund map(ResultSet rs, int row) throws SQLException {
        return new Refund(
                rs.getObject("id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("return_id", UUID.class),
                Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                Refund.Status.valueOf(rs.getString("status")),
                rs.getString("provider_reference"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
