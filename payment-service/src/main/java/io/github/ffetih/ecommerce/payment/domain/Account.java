package io.github.ffetih.ecommerce.payment.domain;

/** Ledger accounts. Debits are positive amounts, credits negative. */
public enum Account {
    /** Money the payment provider owes the shop for captured payments, until it settles. */
    PROVIDER_RECEIVABLE,
    /** Revenue from sold orders. Credited on capture, debited back on refund. */
    SALES
}
