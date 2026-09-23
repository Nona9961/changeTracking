package com.nona.changeTracking.bench.result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares two JMH native result files and produces the difference table of the comparison.
 * <p>
 * The two sources may be two consecutive runs of the same version or runs of two different versions;
 * the comparator only needs both result files. Entries are paired on {@link BenchmarkResultEntry#key()}
 * and compared on the two archived metrics (time per operation and allocated bytes per operation).
 */
public final class BenchmarkResultComparator {

    /** Metric name used for the JMH primary metric (time per operation). */
    public static final String PRIMARY_METRIC_NAME = "primaryMetric";

    /** Metric name used for the allocation profiler metric (allocated bytes per operation). */
    public static final String ALLOCATION_METRIC_NAME = "gc.alloc.rate.norm";

    /** Suffix describing an entry that only the first result holds. */
    private static final String ONLY_IN_FIRST = ": only in first";

    /** Suffix describing an entry that only the second result holds. */
    private static final String ONLY_IN_SECOND = ": only in second";

    /** Logger of this comparator. */
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkResultComparator.class);

    /**
     * Private constructor: this class is a static comparison entry point.
     */
    private BenchmarkResultComparator() {
    }

    /**
     * Compares the two given JMH native result files.
     * <p>
     * Both files are parsed with
     * {@link JmhResultJsonParser#parse(Path)}; a file without entries throws
     * {@link IllegalStateException} (nothing to compare, and an empty result silently reported as an
     * empty table would hide a failed run); entries present in both results are compared on
     * {@value #PRIMARY_METRIC_NAME} and {@value #ALLOCATION_METRIC_NAME} through
     * {@link MetricDelta#between(String, String, MetricValue, MetricValue)}; an entry present in one
     * result only is the single kind of incomparable entry and is reported as
     * {@code <entryKey>: only in first} or {@code <entryKey>: only in second}. Two values of one
     * metric that do not share a unit are not comparable at all: they throw
     * {@link IllegalStateException} with the message
     * {@code <entryKey>#<metricName>: scoreUnit mismatch (<firstUnit> vs <secondUnit>)} and reject the
     * whole comparison, so no unit is translated, no metric is skipped silently and no partial table is
     * produced. The two labels of the produced table are the paths of the two files.
     *
     * @param firstResultFile  path of the first JMH native result JSON, must not be null
     * @param secondResultFile path of the second JMH native result JSON, must not be null
     * @return the difference table of the two results
     * @throws NullPointerException         if an argument is null
     * @throws IllegalStateException        if a result file does not match the JMH result shape, holds no entries, or the two values of one metric do not share a unit
     * @throws java.io.UncheckedIOException if a result file cannot be read
     */
    public static ResultDiff compare(final Path firstResultFile, final Path secondResultFile) {
        Objects.requireNonNull(firstResultFile, "firstResultFile");
        Objects.requireNonNull(secondResultFile, "secondResultFile");
        LOG.info("Comparing the JMH result files {} and {}", firstResultFile, secondResultFile);
        final List<BenchmarkResultEntry> firstEntries = JmhResultJsonParser.parse(firstResultFile);
        final List<BenchmarkResultEntry> secondEntries = JmhResultJsonParser.parse(secondResultFile);
        if (firstEntries.isEmpty()) {
            throw new IllegalStateException("The first result holds no entries: " + firstResultFile);
        }
        if (secondEntries.isEmpty()) {
            throw new IllegalStateException("The second result holds no entries: " + secondResultFile);
        }
        final Map<String, BenchmarkResultEntry> firstByKey = byKey(firstEntries);
        final Map<String, BenchmarkResultEntry> secondByKey = byKey(secondEntries);
        final List<MetricDelta> deltas = new ArrayList<>();
        final List<String> incomparable = new ArrayList<>();
        for (final Map.Entry<String, BenchmarkResultEntry> first : firstByKey.entrySet()) {
            final BenchmarkResultEntry second = secondByKey.get(first.getKey());
            if (second == null) {
                incomparable.add(first.getKey() + ONLY_IN_FIRST);
                continue;
            }
            deltas.add(delta(first.getKey(), PRIMARY_METRIC_NAME,
                    first.getValue().primaryMetric(), second.primaryMetric()));
            deltas.add(delta(first.getKey(), ALLOCATION_METRIC_NAME,
                    first.getValue().allocationMetric(), second.allocationMetric()));
        }
        secondByKey.keySet().stream()
                .filter(key -> !firstByKey.containsKey(key))
                .forEach(key -> incomparable.add(key + ONLY_IN_SECOND));
        return new ResultDiff(firstResultFile.toString(), secondResultFile.toString(), deltas, incomparable);
    }

    /**
     * Indexes the entries of one result by their pairing key.
     *
     * @param entries the entries of one result
     * @return the entries by pairing key, keeping the first entry of a key that occurs twice
     */
    private static Map<String, BenchmarkResultEntry> byKey(final List<BenchmarkResultEntry> entries) {
        final Map<String, BenchmarkResultEntry> byKey = new LinkedHashMap<>();
        entries.forEach(entry -> byKey.putIfAbsent(entry.key(), entry));
        return byKey;
    }

    /**
     * Compares one metric of one paired entry.
     *
     * @param entryKey   pairing key of the compared entry
     * @param metricName name of the compared metric
     * @param first      value of the first result
     * @param second     value of the second result
     * @return the delta of the metric
     * @throws IllegalStateException if the two values do not share the unit of the metric
     */
    private static MetricDelta delta(final String entryKey, final String metricName,
                                     final MetricValue first, final MetricValue second) {
        if (!MetricValue.sameUnit(first, second)) {
            throw new IllegalStateException(MetricDelta.unitMismatchMessage(entryKey, metricName, first, second));
        }
        return MetricDelta.between(entryKey, metricName, first, second);
    }
}