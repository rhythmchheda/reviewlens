package com.reviewlens.filter;

import com.reviewlens.model.Review;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record FilterResult(List<Review> kept, List<RejectedReview> rejected) {

    public Map<String, Long> countsByFilter() {
        return rejected.stream().collect(
                Collectors.groupingBy(RejectedReview::filter, TreeMap::new, Collectors.counting()));
    }
}
