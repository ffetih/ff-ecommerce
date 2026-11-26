package io.github.ffetih.ecommerce.payment.adapter.out.persistence;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerEntry;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger tables check themselves (V2 migration): entries are inserted in one statement and must
 * balance, and nothing is ever updated or deleted.
 */
@Component
class JdbcLedger implements Ledger {

    private final JdbcClient jdbc;

    JdbcLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean book(LedgerTransaction transaction) {
        int inserted = jdbc.sql("""
                        INSERT INTO ledger_transactions (id, payment_id, reference, kind, booked_at)
                        VALUES (:id, :paymentId, :reference, :kind, :bookedAt)
                        ON CONFLICT (reference) DO NOTHING""")
                .param("id", transaction.id())
                .param("paymentId", transaction.paymentId())
                .param("reference", transaction.reference())
                .param("kind", transaction.kind().name())
                .param("bookedAt", JdbcPayments.utc(transaction.bookedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        insertEntries(transaction);
        return true;
    }

    /** All entries in one statement: the balance trigger checks the transaction as a whole. */
    private void insertEntries(LedgerTransaction transaction) {
        List<String> rows = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("transactionId", transaction.id());
        params.put("currency", transaction.currency().getCurrencyCode());
        for (int line = 1; line <= transaction.entries().size(); line++) {
            LedgerEntry entry = transaction.entries().get(line - 1);
            rows.add("(:transactionId, %1$d, :account%1$d, :amount%1$d, :currency)".formatted(line));
            params.put("account" + line, entry.account().name());
            params.put("amount" + line, entry.amount());
        }
        jdbc.sql("INSERT INTO ledger_entries (transaction_id, line_number, account, amount, currency) VALUES "
                        + String.join(", ", rows))
                .params(params)
                .update();
    }

    @Override
    public List<LedgerTransaction> transactionsOf(UUID paymentId) {
        record Header(UUID id, String reference, String kind, OffsetDateTime bookedAt) {
        }
        List<Header> headers = jdbc.sql("""
                        SELECT id, reference, kind, booked_at FROM ledger_transactions
                        WHERE payment_id = :paymentId ORDER BY booked_at, id""")
                .param("paymentId", paymentId)
                .query((rs, row) -> new Header(rs.getObject("id", UUID.class), rs.getString("reference"),
                        rs.getString("kind"), rs.getObject("booked_at", OffsetDateTime.class)))
                .list();
        return headers.stream().map(header -> {
            record Line(LedgerEntry entry, String currency) {
            }
            List<Line> lines = jdbc.sql("""
                            SELECT account, amount, currency FROM ledger_entries
                            WHERE transaction_id = :id ORDER BY line_number""")
                    .param("id", header.id())
                    .query((rs, row) -> new Line(
                            new LedgerEntry(Account.valueOf(rs.getString("account")), rs.getBigDecimal("amount")),
                            rs.getString("currency")))
                    .list();
            return new LedgerTransaction(header.id(), paymentId, header.reference(),
                    LedgerTransaction.Kind.valueOf(header.kind()), Currency.getInstance(lines.getFirst().currency()),
                    lines.stream().map(Line::entry).toList(), header.bookedAt().toInstant());
        }).toList();
    }

    @Override
    public BigDecimal balance(Account account, Currency currency) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE account = :account AND currency = :currency")
                .param("account", account.name())
                .param("currency", currency.getCurrencyCode())
                .query(BigDecimal.class)
                .single();
    }
}
