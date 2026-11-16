package com.reviewlens.web;

import com.reviewlens.model.Product;
import com.reviewlens.model.Review;
import com.reviewlens.store.InMemoryStore;
import com.reviewlens.summary.SummaryResponse;
import com.reviewlens.summary.SummaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    public record CreateProductRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record CreateReviewRequest(@Min(1) @Max(5) int rating,
                                      @NotBlank @Size(max = 100) String author,
                                      @NotBlank @Size(max = 5000) String text) {
    }

    private final InMemoryStore store;
    private final SummaryService summaryService;

    public ProductController(InMemoryStore store, SummaryService summaryService) {
        this.store = store;
        this.summaryService = summaryService;
    }

    @GetMapping
    public List<Product> list() {
        return store.allProducts();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Product create(@Valid @RequestBody CreateProductRequest req) {
        return store.addProduct(req.name());
    }

    @GetMapping("/{id}/reviews")
    public List<Review> reviews(@PathVariable long id) {
        requireProduct(id);
        return store.reviewsFor(id);
    }

    @PostMapping("/{id}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Review addReview(@PathVariable long id, @Valid @RequestBody CreateReviewRequest req) {
        requireProduct(id);
        return store.addReview(id, req.rating(), req.author(), req.text());
    }

    @GetMapping("/{id}/summary")
    public SummaryResponse summary(@PathVariable long id) {
        return summaryService.summarize(id);
    }

    private void requireProduct(long id) {
        store.findProduct(id).orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }
}
