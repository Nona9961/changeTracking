package com.nona.changeTracking.bench.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One benchmark result entry read from a JMH native result JSON: the benchmark name, its parameter
 * binding and the two archived metrics (time per operation and allocated bytes per operation).
 * <p>
 * JMH identifies an entry by the benchmark name plus its parameters, so both are part of the entry
 * and the comparison pairs are formed on {@link #key()}.
 *
 * @param benchmark       fully qualified benchmark name, never blank
 * @param params          parameter binding of the entry, never null (possibly empty)
 * @param primaryMetric   time metric of the entry (JMH {@code primaryMetric})
 * @param allocationMetric allocated bytes per operation (JMH {@code gc.alloc.rate.norm})
 */
public record BenchmarkResultEntry(String benchmark,
                                   Map<String, String> params,
                                   MetricValue primaryMetric,
                                   MetricValue allocationMetric) {

    /**
     * Validates the entry and takes an immutable copy of the parameter binding.
     * <p>
     * Contract implemented in the green phase: a null or blank benchmark name throws
     * {@link IllegalArgumentException}; the parameter map is copied defensively, so the entry stays
     * immutable; the two metrics are mandatory.
     *
     * @param benchmark        fully qualified benchmark name
     * @param params           parameter binding of the entry
     * @param primaryMetric    time metric of the entry
     * @param allocationMetric allocated bytes per operation
     */
    public BenchmarkResultEntry {
        if (benchmark == null || benchmark.isBlank()) {
            throw new IllegalArgumentException("benchmark name must not be blank, got: " + benchmark);
        }
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(primaryMetric, "primaryMetric");
        Objects.requireNonNull(allocationMetric, "allocationMetric");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * Returns the pairing key of this entry: the benchmark name followed by the parameters in
     * lexicographic order, for example
     * {@code com.nona.Example.benchmark[changedFieldCount=0]}.
     * <p>
     * The key is derived from the content, not from the JSON field order, so two results of the same
     * run pair up even when their parameter objects are written in a different order. An entry
     * without parameters uses the benchmark name alone.
     *
     * @return the deterministic pairing key of this entry
     */
    public String key() {
        if (params.isEmpty()) {
            return benchmark;
        }
        final String renderedParams = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(param -> param.getKey() + "=" + param.getValue())
                .collect(Collectors.joining(","));
        return benchmark + "[" + renderedParams + "]";
    }
}