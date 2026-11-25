package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface ProductJpaRepository extends JpaRepository<ProductEntity, String> {

    List<ProductEntity> findAllBySkuInOrderBySku(Collection<String> skus);
}
