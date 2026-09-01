package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AdminMetricsQuery;

import java.time.Instant;
import java.util.List;

public record TrafficResponse(Instant from, Instant to, String bucket, List<Bucket> buckets) {

    public record Bucket(Instant start, long in, long out) {}

    public static TrafficResponse from(Instant from, Instant to, List<AdminMetricsQuery.TrafficBucket> src) {
        List<Bucket> buckets = src.stream()
                .map(b -> new Bucket(b.bucketStart(), b.in(), b.out()))
                .toList();
        return new TrafficResponse(from, to, "hour", buckets);
    }
}
