package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
class SpringTransactions implements Transactions {

    private final TransactionTemplate template;

    SpringTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
