package com.nona.changeTracking.bench.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Difference table of two benchmark result files, the artefact AC3 asks for.
 * <p>
 * The table has two parts: the comparable deltas (entries present in both results, compared metric
 * by metric with a significance verdict) and the incomparable entries — the ones present in one
 * result only — which are listed by reason instead of being compared silently. Two values of one
 * metric that do not share a unit are not an incomparable entry: they reject the comparison before a
 * table exists.
 *
 * @param firstLabel   human readable label of the first result, never null
 * @param secondLabel  human readable label of the second result, never null
 * @param deltas       comparable metric deltas, immutable
 * @param incomparable incomparable entry descriptions, immutable
 */
public record ResultDiff(String firstLabel,
                         String secondLabel,
                         List<MetricDelta> deltas,
                         List<String> incomparable) {

    /** Prefix of the line carrying an entry that only one of the two results holds. */
    private static final String INCOMPARABLE_PREFIX = "incomparable|";

    /**
     * Takes immutable copies of the two lists.
     * <p>
     * Contract implemented in the green phase: both lists are copied defensively and the labels must
     * not be null.
     *
     * @param firstLabel   label of the first result
     * @param secondLabel  label of the second result
     * @param deltas       comparable metric deltas
     * @param incomparable incomparable entry descriptions
     */
    public ResultDiff {
        Objects.requireNonNull(firstLabel, "firstLabel");
        Objects.requireNonNull(secondLabel, "secondLabel");
        deltas = List.copyOf(deltas);
        incomparable = List.copyOf(incomparable);
    }

    /**
     * Returns the deltas whose verdict is {@link Significance#SIGNIFICANT}.
     *
     * @return the significant deltas, in the order of the table
     */
    public List<MetricDelta> significantDeltas() {
        return deltasOf(Significance.SIGNIFICANT);
    }

    /**
     * Returns the deltas whose verdict is {@link Significance#INSIGNIFICANT}.
     *
     * @return the insignificant deltas, in the order of the table
     */
    public List<MetricDelta> insignificantDeltas() {
        return deltasOf(Significance.INSIGNIFICANT);
    }

    /**
     * Renders the difference table as text.
     * <p>
     * Contract implemented in the green phase, one line per element:
     * <ol>
     *   <li>the header line {@code Benchmark result diff: <firstLabel> vs <secondLabel>}</li>
     *   <li>the column line {@code metric|entry|first|second|delta|significance}</li>
     *   <li>one line per delta, pipe separated:
     *       {@code <metricName>|<entryKey>|<firstScore> <unit>|<secondScore> <unit>|<delta>|<verdict>}</li>
     *   <li>one line per incomparable entry: {@code incomparable|<description>}</li>
     * </ol>
     * A table without deltas and without incomparable entries still renders the two header lines.
     *
     * @return the rendered difference table
     */
    public String toTable() {
        final List<String> lines = new ArrayList<>();
        lines.add(headerLine());
        lines.add(columnLine());
        for (final MetricDelta delta : deltas) {
            lines.add(deltaLine(delta));
        }
        for (final String description : incomparable) {
            lines.add(INCOMPARABLE_PREFIX + description);
        }
        return String.join("\n", lines);
    }

    /**
     * Filters the deltas of one verdict.
     *
     * @param verdict the verdict to keep
     * @return the deltas carrying the verdict, in the order of the table
     */
    private List<MetricDelta> deltasOf(final Significance verdict) {
        return deltas.stream().filter(delta -> delta.significance() == verdict).toList();
    }

    /**
     * Renders the header line of the table.
     *
     * @return the header line
     */
    private String headerLine() {
        return "Benchmark result diff: " + firstLabel + " vs " + secondLabel;
    }

    /**
     * Renders the column line of the table.
     *
     * @return the column line
     */
    private String columnLine() {
        return String.join("|", "metric", "entry", "first", "second", "delta", "significance");
    }

    /**
     * Renders one delta as a pipe separated line.
     *
     * @param delta the delta to render
     * @return the rendered delta line
     */
    private String deltaLine(final MetricDelta delta) {
        return String.join("|",
                delta.metricName(),
                delta.entryKey(),
                delta.firstScore() + " " + delta.unit(),
                delta.secondScore() + " " + delta.unit(),
                Double.toString(delta.delta()),
                delta.significance().name().toLowerCase(Locale.ROOT));
    }
}