package io.github.ffetih.ecommerce.payment.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** One line of a ledger transaction: a positive amount debits the account, a negative one credits it. */
public record LedgerEntry(Account account, BigDecimal amount) {

    public LedgerEntry {
        Objects.requireNonNull(account, "account is required");
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("A ledger entry moves money: its amount cannot be zero");
        }
    }
}
