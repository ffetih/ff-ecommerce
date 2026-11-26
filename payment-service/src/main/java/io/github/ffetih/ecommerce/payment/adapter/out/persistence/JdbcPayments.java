package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A payment is one flat row, so plain SQL is enough; no ORM in this service. */
@Component
class JdbcPayments implements PaymentRepository {

    private static final String COLUMNS = """
            id, order_id, customer_id, amount, currency, card_token, status, provider_reference,
            void_requested, failure_reason, created_at, updated_at""";

    private final JdbcClient jdbc;

    JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(JdbcPayments::map)
                .optional();
    }

    @Override
    public Optional<Payment> lockByOrderId(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE order_id = :orderId FOR UPDATE")
                .param("orderId", orderId)
                .query(JdbcPayments::map)
                .optional();
    }

    @Override
    public List<Payment> findInFlight(Instant updatedBefore, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM payments
                        WHERE status IN ('AUTHORIZING', 'CAPTURING', 'VOIDING') AND updated_at < :before
                        ORDER BY updated_at
                        LIMIT :limit""")
                .param("before", utc(updatedBefore))
                .param("limit", limit)
                .query(JdbcPayments::map)
                .list();
    }

    @Override
    public void save(Payment payment) {
        jdbc.sql("""
                        INSERT INTO payments (%s)
                        VALUES (:id, :orderId, :customerId, :amount, :currency, :cardToken, :status, :providerReference,
                                :voidRequested, :failureReason, :createdAt, :updatedAt)
                        ON CONFLICT (id) DO UPDATE SET
                            status = EXCLUDED.status,
                            provider_reference = EXCLUDED.provider_reference,
                            void_requested = EXCLUDED.void_requested,
                            failure_reason = EXCLUDED.failure_reason,
                            updated_at = EXCLUDED.updated_at""".formatted(COLUMNS))
                .param("id", payment.id())
                .param("orderId", payment.orderId())
                .param("customerId", payment.customerId())
                .param("amount", payment.amount().amount())
                .param("currency", payment.amount().currency().getCurrencyCode())
                .param("cardToken", payment.cardToken())
                .param("status", payment.status().name())
                .param("providerReference", payment.providerReference())
                .param("voidRequested", payment.voidRequested())
                .param("failureReason", payment.failureReason())
                .param("createdAt", utc(payment.createdAt()))
                .param("updatedAt", utc(payment.updatedAt()))
                .update();
    }

    /**
     * The whole limit is the {@code WHERE}: two refunds at the same moment are applied one after the
     * other on the locked row, and the second sees the first. The check constraint refuses the rest.
     */
    @Override
    public boolean reserveRefund(UUID paymentId, BigDecimal amount) {
        return jdbc.sql("""
                        UPDATE payments SET refunded_amount = refunded_amount + :amount
                        WHERE id = :id AND status = 'CAPTURED' AND refunded_amount + :amount <= amount""")
                .param("id", paymentId)
                .param("amount", amount)
                .update() == 1;
    }

    @Override
    public void releaseRefund(UUID paymentId, BigDecimal amount) {
        jdbc.sql("UPDATE payments SET refunded_amount = refunded_amount - :amount WHERE id = :id")
                .param("id", paymentId)
                .param("amount", amount)
                .update();
    }

    @Override
    public BigDecimal refundedAmount(UUID paymentId) {
        return jdbc.sql("SELECT refunded_amount FROM payments WHERE id = :id")
                .param("id", paymentId)
                .query(BigDecimal.class)
                .single();
    }

    private static Payment map(ResultSet rs, int row) throws SQLException {
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                Money.of(rs.getBigDecimal("amount"), rs.getString("currency")),
                rs.getString("card_token"),
                Payment.Status.valueOf(rs.getString("status")),
                rs.getString("provider_reference"),
                rs.getBoolean("void_requested"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
