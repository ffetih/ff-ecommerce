package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.application.port.Transactions;
import io.github.ffetih.ecommerce.catalog.domain.Money;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;

import java.util.Collection;
import java.util.List;

public class ProductService {

    private final ProductRepository products;
    private final StockRepository stock;
    private final Transactions transactions;

    public ProductService(ProductRepository products, StockRepository stock, Transactions transactions) {
        this.products = products;
        this.stock = stock;
        this.transactions = transactions;
    }

    /**
     * Creates the product together with an empty stock row, so every product can be stocked and
     * reserved. Two concurrent creations of one SKU both pass the check; the insert (never an upsert)
     * then meets the primary key, and the second gets the same 409.
     */
    public Product create(Sku sku, String name, Money unitPrice) {
        Product product = Product.create(sku, name, unitPrice);
        return transactions.inTransaction(() -> {
            if (products.findBySku(sku).isPresent()) {
                throw ConflictException.productExists(sku);
            }
            Product saved = products.insert(product);
            stock.save(StockItem.empty(sku));
            return saved;
        });
    }

    public Product changePrice(Sku sku, Money newPrice) {
        return transactions.inTransaction(() -> {
            Product product = products.findBySku(sku).orElseThrow(() -> NotFoundException.product(sku));
            return products.update(product.changePrice(newPrice));
        });
    }

    /**
     * Takes a product off sale: order-service refuses new orders for it, while orders already placed
     * keep their reservation and complete. Deactivating an inactive product changes nothing.
     */
    public Product deactivate(Sku sku) {
        return transactions.inTransaction(() -> {
            Product product = products.findBySku(sku).orElseThrow(() -> NotFoundException.product(sku));
            return product.active() ? products.update(product.deactivate()) : product;
        });
    }

    public Product get(Sku sku) {
        return products.findBySku(sku).orElseThrow(() -> NotFoundException.product(sku));
    }

    /** Products for the given SKUs, or all products when none are given. Unknown SKUs are skipped. */
    public List<Product> find(Collection<Sku> skus) {
        return skus.isEmpty() ? products.findAll() : products.findAllBySku(skus);
    }
}
