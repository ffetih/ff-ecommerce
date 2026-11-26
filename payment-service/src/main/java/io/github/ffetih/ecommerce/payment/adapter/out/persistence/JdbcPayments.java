package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
