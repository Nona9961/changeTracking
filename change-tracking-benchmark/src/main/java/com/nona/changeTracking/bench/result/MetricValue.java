package com.nona.changeTracking.bench.result;

/**
 * One measured metric of one benchmark result entry: the reported score, the reported error and
 * the unit of both numbers.
 * <p>
 * The value is non null by construction: the unit is mandatory (a score without a unit cannot be
 * compared or reported) and a missing {@code scoreError} is represented by the explicit zero value
 * at the parsing boundary instead of a nullable field.
 *
 * @param score      the reported score of the metric
 * @param scoreError the reported error of the score, {@code 0} when the result carries none
 * @param scoreUnit  the unit of score and scoreError, never null or blank
 */
public record MetricValue(double score, double scoreError, String scoreUnit) {

    /**
     * Validates the metric value.
     * <p>
     * Contract implemented in the green phase: a {@code null} or blank unit throws
     * {@link IllegalArgumentException}; a negative or {@code NaN} error throws
     * {@link IllegalArgumentException}; the score itself may be any double, including zero and
     * {@link Double#MAX_VALUE}.
     *
     * @param score      the reported score of the metric
     * @param scoreError the reported error of the score
     * @param scoreUnit  the unit of score and scoreError
     */
    public MetricValue {
        if (scoreUnit == null || scoreUnit.isBlank()) {
            throw new IllegalArgumentException("scoreUnit must not be blank, got: " + scoreUnit);
        }
        if (Double.isNaN(scoreError) || scoreError < 0.0) {
            throw new IllegalArgumentException("scoreError must not be negative or NaN, got: " + scoreError);
        }
    }
}