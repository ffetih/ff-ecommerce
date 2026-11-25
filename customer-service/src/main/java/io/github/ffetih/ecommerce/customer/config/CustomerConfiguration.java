package io.github.ffetih.ecommerce.customer.config;

import io.github.ffetih.ecommerce.customer.application.CustomerService;
import io.github.ffetih.ecommerce.customer.application.port.CustomerRepository;
import io.github.ffetih.ecommerce.customer.application.port.Transactions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class CustomerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CustomerService customerService(CustomerRepository customers, Transactions transactions, Clock clock) {
        return new CustomerService(customers, transactions, clock);
    }
}
