package com.nona.changeTracking.bench.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Report of one retained memory scenario: which result was held, on which frozen load, and the
 * retained footprint of that held result.
 * <p>
 * The report renders to a stable, line oriented text form: one {@code key=value} header line per fact
 * (scenario, shape, held results, retained bytes, retained objects) followed by one
 * {@code retainedClass=<class name> count=<n> bytes=<n>} line per class footprint in the order of the
 * footprint. Every line starts with a fixed key, so a comparison script pairs two reports by line key
 * and a reviewer reads the dominant retained objects at the top of the class list.
 *
 * @param scenario  the measured retention scenario, never null
 * @param shape     name of the frozen load shape the scenario was measured on, never blank
 * @param footprint retained footprint of the held result, never null
 */
public record RetainedMemoryReport(RetainedMemoryScenario scenario, String shape, RetainedFootprint footprint) {

    /**
     * Validates the report fields.
     *
     * @param scenario  the measured retention scenario, never null
     * @param shape     name of the frozen load shape, never blank
     * @param footprint retained footprint of the held result, never null
     * @throws NullPointerException     if the scenario, the shape or the footprint is null
     * @throws IllegalArgumentException if the shape is blank
     */
    public RetainedMemoryReport {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(shape, "shape");
        if (shape.isBlank()) {
            throw new IllegalArgumentException("shape must not be blank");
        }
        Objects.requireNonNull(footprint, "footprint");
    }

    /**
     * Renders this report to its stable line form.
     *
     * @return the report lines, header lines first and one class line per class footprint
     */
    public List<String> render() {
        final List<String> lines = new ArrayList<>();
        lines.add("scenario=" + scenario.commandLineName());
        lines.add("shape=" + shape);
        lines.add("heldResults=" + heldResultTokens());
        lines.add("retainedBytes=" + footprint.retainedBytes());
        lines.add("retainedObjects=" + footprint.objectCount());
        for (final ClassFootprint classFootprint : footprint.classFootprints()) {
            lines.add("retainedClass=" + classFootprint.className()
                    + " count=" + classFootprint.objectCount() + " bytes=" + classFootprint.bytes());
        }
        return lines;
    }

    /**
     * Joins the stable tokens of the results the scenario holds, in acquisition order.
     *
     * @return the comma separated held result tokens
     */
    private String heldResultTokens() {
        return String.join(",", scenario.heldResultViews().stream()
                .map(RetainedMemoryScenario.ResultView::token)
                .toList());
    }
}
