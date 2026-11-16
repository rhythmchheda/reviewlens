package com.reviewlens.store;

import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-memory storage. Swap for a JPA repository later without touching the service layer. */
@Component
public class InMemoryStore {

    private final Map<Long, Product> products = new ConcurrentHashMap<>();
    private final Map<Long, List<Review>> reviewsByProduct = new ConcurrentHashMap<>();
    private final AtomicLong productIds = new AtomicLong();
    private final AtomicLong reviewIds = new AtomicLong();

    public Product addProduct(String name) {
        Product p = new Product(productIds.incrementAndGet(), name);
        products.put(p.id(), p);
        reviewsByProduct.put(p.id(), new CopyOnWriteArrayList<>());
        return p;
    }

    public Optional<Product> findProduct(long id) {
        return Optional.ofNullable(products.get(id));
    }

    public List<Product> allProducts() {
        return products.values().stream().sorted(Comparator.comparingLong(Product::id)).toList();
    }

    public Review addReview(long productId, int rating, String author, String text) {
        Review r = new Review(reviewIds.incrementAndGet(), productId, rating, author, text, Instant.now());
        reviewsByProduct.get(productId).add(r);
        return r;
    }

    public List<Review> reviewsFor(long productId) {
        return List.copyOf(reviewsByProduct.getOrDefault(productId, List.of()));
    }
}
