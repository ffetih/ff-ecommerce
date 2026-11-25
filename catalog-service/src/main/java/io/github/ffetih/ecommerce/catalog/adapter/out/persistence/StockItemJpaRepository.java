package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface StockItemJpaRepository extends JpaRepository<StockItemEntity, String> {

    /**
     * {@code SELECT ... ORDER BY sku FOR UPDATE}: PostgreSQL sorts first and locks rows in that order,
     * so every caller acquires locks on shared SKUs in the same sequence and cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockItemEntity s WHERE s.sku IN :skus ORDER BY s.sku")
    List<StockItemEntity> lockAllBySkuIn(@Param("skus") Collection<String> skus);
}
