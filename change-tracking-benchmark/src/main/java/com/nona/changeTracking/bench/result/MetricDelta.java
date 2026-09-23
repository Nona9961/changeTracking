package com.nona.changeTracking.bench.result;

import java.util.Objects;

/**
 * Delta of one metric of one paired result entry, with the significance verdict required by AC3.
 * <p>
 * The delta is a value object: the two compared values, the difference in the metric unit, the
 * error threshold taken from the two reports and the verdict. It carries no reference to the
 * results it was derived from.
 *
 * @param entryKey      pairing key of the compared entry
 * @param metricName    name of the compared metric
 * @param unit          unit of both scores, never blank
 * @param firstScore    score of the first result
 * @param secondScore   score of the second result
 * @param delta         {@code secondScore - firstScore}, in the metric unit
 * @param threshold     reported error used as the significance threshold
 * @param significance  verdict of the comparison
 */
public record MetricDelta(String entryKey,
                          String metricName,
                          String unit,
                          double firstScore,
                          double secondScore,
                          double delta,
                          double threshold,
                          Significance significance) {

    /**
     * Validates the delta identity fields.
     * <p>
     * Contract implemented in the green phase: a null or blank entry key, metric name or unit throws
     * {@link IllegalArgumentException}; the numbers are taken as computed by
     * {@link #between(String, String, MetricValue, MetricValue)}.
     *
     * @param entryKey     pairing key of the compared entry
     * @param metricName   name of the compared metric
     * @param unit         unit of both scores
     * @param firstScore   score of the first result
     * @param secondScore  score of the second result
     * @param delta        difference in the metric unit
     * @param threshold    reported error used as the significance threshold
     * @param significance verdict of the comparison
     */
    public MetricDelta {
        if (entryKey == null || entryKey.isBlank()) {
            throw new IllegalArgumentException("entry key must not be blank, got: " + entryKey);
        }
        if (metricName == null || metricName.isBlank()) {
            throw new IllegalArgumentException("metric name must not be blank, got: " + metricName);
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("metric unit must not be blank, got: " + unit);
        }
        Objects.requireNonNull(significance, "significance");
    }

    /**
     * Creates the delta of one metric of one paired entry.
     * <p>
     * Contract implemented in the green phase: both values must carry the same unit, otherwise
     * {@link IllegalArgumentException} is thrown; the comparison layer reads that as "this comparison
     * cannot be made" and rejects the whole comparison instead of translating one unit into the other
     * or listing the metric as an entry that cannot be compared.
     * The threshold is the larger of the two reported errors (a missing error is passed as zero).
     * The delta is {@code second.score() - first.score()}. The verdict is
     * {@link Significance#INSIGNIFICANT} when the delta is zero or when its absolute value does not
     * exceed the threshold, {@link Significance#SIGNIFICANT} otherwise.
     *
     * @param entryKey   pairing key of the compared entry, must not be null
     * @param metricName name of the compared metric, must not be null
     * @param first      value of the first result, must not be null
     * @param second     value of the second result, must not be null
     * @return the delta of the metric between the two results
     * @throws NullPointerException     if an argument is null
     * @throws IllegalArgumentException if the two values do not share the same unit
     */
    public static MetricDelta between(final String entryKey,
                                      final String metricName,
                                      final MetricValue first,
                                      final MetricValue second) {
        Objects.requireNonNull(entryKey, "entryKey");
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!first.scoreUnit().equals(second.scoreUnit())) {
            throw new IllegalArgumentException(entryKey + "#" + metricName + ": scoreUnit mismatch ("
                    + first.scoreUnit() + " vs " + second.scoreUnit() + ")");
        }
        final double delta = second.score() - first.score();
        final double threshold = Math.max(first.scoreError(), second.scoreError());
        final Significance significance = delta == 0.0 || Math.abs(delta) <= threshold
                ? Significance.INSIGNIFICANT
                : Significance.SIGNIFICANT;
        return new MetricDelta(entryKey, metricName, first.scoreUnit(), first.score(), second.score(),
                delta, threshold, significance);
    }
}