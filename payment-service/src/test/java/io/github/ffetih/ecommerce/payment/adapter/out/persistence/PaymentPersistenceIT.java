package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentPersistenceIT {

    // Microseconds: PostgreSQL's timestamp precision, so a round trip compares equal.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    // A currency no other test uses, so balances start at zero.
    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    PaymentRepository payments;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcClient jdbc;

    private Payment saved(String currency) {
        Payment payment = Payment.start(UUID.randomUUID(), UUID.randomUUID(), Money.of(new BigDecimal("49.9"), currency),
                "tok_ok", NOW);
        payments.save(payment);
        return payment;
    }

    @Test
    void savesAndUpdatesAPayment() {
        Payment started = saved("TRY");
        assertThat(payments.findByOrderId(started.orderId())).contains(started);

        Payment remembered = started.requestVoid(NOW.plusSeconds(1)).authorized("AUTH-1", NOW.plusSeconds(2));
        payments.save(remembered);

        Payment found = payments.lockByOrderId(started.orderId()).orElseThrow();
        assertThat(found).isEqualTo(remembered);
        assertThat(found.status()).isEqualTo(Payment.Status.VOIDING);
        assertThat(found.voidRequested()).isTrue();
        assertThat(found.amount().amount()).isEqualTo(new BigDecimal("49.90"));
    }

    @Test
    void anOrderHasOnePayment() {
        Payment first = saved("TRY");
        Payment second = Payment.start(first.orderId(), first.customerId(), first.amount(), "tok_ok", NOW);

        assertThatThrownBy(() -> payments.save(second)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void bookingACaptureTwiceBooksItOnce() {
        Payment captured = saved("EUR").authorized("AUTH-1", NOW).requestCapture(NOW).captured(NOW);
        payments.save(captured);

        assertThat(ledger.book(LedgerTransaction.capture(captured, NOW))).isTrue();
        assertThat(ledger.book(LedgerTransaction.capture(captured, NOW))).isFalse();

        assertThat(ledger.transactionsOf(captured.id())).singleElement()
                .satisfies(booked -> assertThat(booked.entries()).hasSize(2));
        assertThat(ledger.balance(Account.PROVIDER_RECEIVABLE, EUR)).isEqualByComparingTo("49.90");
        assertThat(ledger.balance(Account.SALES, EUR)).isEqualByComparingTo("-49.90");
    }

    @Test
    void theDatabaseRejectsAnUnbalancedTransaction() {
        Payment payment = saved("TRY");
        UUID transaction = insertTransaction(payment);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO ledger_entries (transaction_id, line_number, account, amount, currency)
                        VALUES (:id, 1, 'PROVIDER_RECEIVABLE', 10.00, 'TRY'), (:id, 2, 'SALES', -9.99, 'TRY')""")
                .param("id", transaction)
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("does not balance");
    }

    @Test
    void theDatabaseRefusesToChangeHistory() {
        Payment captured = saved("TRY").authorized("AUTH-1", NOW).requestCapture(NOW).captured(NOW);
        payments.save(captured);
        LedgerTransaction booked = LedgerTransaction.capture(captured, NOW);
        ledger.book(booked);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1 WHERE transaction_id = :id")
                .param("id", booked.id())
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_transactions WHERE id = :id")
                .param("id", booked.id())
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
    }

    private UUID insertTransaction(Payment payment) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger_transactions (id, payment_id, reference, kind, booked_at)
                        VALUES (:id, :paymentId, :reference, 'CAPTURE', now())""")
                .param("id", id)
                .param("paymentId", payment.id())
                .param("reference", "manual-" + id)
                .update();
        return id;
    }
}
