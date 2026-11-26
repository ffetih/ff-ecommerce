package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** The append-only double-entry ledger. */
public interface Ledger {

    /** Books the transaction; returns {@code false} if one with the same reference is already booked. */
    boolean book(LedgerTransaction transaction);

    List<LedgerTransaction> transactionsOf(UUID paymentId);

    /** The sum of all entries of the account in the currency: positive means a debit balance. */
    BigDecimal balance(Account account, Currency currency);
}
