package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.RefundRepository;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Refund;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentPersistenceIT {

    // An hour ahead: these payments are rows to read back, and a resolver running in another test's
    // context must not see them as stale calls to finish. Microseconds: PostgreSQL's precision.
    private static final Instant NOW = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
    // A currency no other test uses, so balances start at zero.
    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    PaymentRepository payments;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RefundRepository refunds;

    @Autowired
    Transactions transactions;

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

    /** Ten refunds of 20.00 against a capture of 49.90, all at once: exactly two fit, whatever the timing. */
    @Test
    void concurrentRefundsNeverExceedTheCapture() throws Exception {
        Payment captured = saved("TRY").authorized("AUTH-1", NOW).requestCapture(NOW).captured(NOW);
        payments.save(captured);
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                threads.submit(() -> {
                    start.await();
                    if (transactions.inTransaction(() -> payments.reserveRefund(captured.id(), new BigDecimal("20.00")))) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(accepted.get()).isEqualTo(2);
        assertThat(payments.refundedAmount(captured.id())).isEqualByComparingTo("40.00");
    }

    @Test
    void onlyACapturedPaymentCanBeRefundedAndTheDatabaseKeepsTheLimit() {
        Payment authorized = saved("TRY").authorized("AUTH-1", NOW);
        payments.save(authorized);

        assertThat(payments.reserveRefund(authorized.id(), new BigDecimal("1.00"))).isFalse();
        assertThatThrownBy(() -> jdbc.sql("UPDATE payments SET refunded_amount = amount + 1 WHERE id = :id")
                .param("id", authorized.id())
                .update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("payments_refunds_within_amount");
    }

    @Test
    void oneRefundPerReturn() {
        Payment captured = saved("TRY").authorized("AUTH-1", NOW).requestCapture(NOW).captured(NOW);
        payments.save(captured);
        UUID returnId = UUID.randomUUID();
        Refund first = Refund.start(captured, returnId, Money.of(new BigDecimal("10.00"), "TRY"), NOW);
        refunds.save(first);

        assertThat(refunds.findByReturnId(returnId)).contains(first);
        assertThatThrownBy(() -> refunds.save(Refund.start(captured, returnId, Money.of(new BigDecimal("10.00"), "TRY"), NOW)))
                .isInstanceOf(DuplicateKeyException.class);
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
