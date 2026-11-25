package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface StockReceiptJpaRepository extends JpaRepository<StockReceiptEntity, UUID> {
}
