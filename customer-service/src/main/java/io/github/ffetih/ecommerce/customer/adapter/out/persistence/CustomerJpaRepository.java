package io.github.ffetih.ecommerce.customer.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CustomerJpaRepository extends JpaRepository<CustomerEntity, UUID> {

    boolean existsByEmail(String email);
}
