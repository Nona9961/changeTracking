package com.nona.changeTracking.bench.memory;

import java.util.List;
import java.util.Objects;

/**
 * The five result retention scenarios of the retained memory measurement, mirroring the five carriers
 * the change time and allocation comparison covers.
 * <p>
 * The scenarios differ in which results of one change detection cycle are held after the cycle: the
 * calculated change set alone, the change set plus the leaf view, the change set plus the complete
 * view, the change set plus two acquisitions of the complete view, or the change set plus both views.
 * Holding the same calculated change set in every scenario makes the leading change set the common
 * baseline, so the footprint difference between two scenarios is the cost of the views they add and
 * the repeated acquisition is measured apart from the repeated calculation.
 */
public enum RetainedMemoryScenario {

    /** Holds the calculated change set alone. */
    CALCULATE_ONLY("calculateOnly"),

    /** Holds the calculated change set and one leaf view acquisition. */
    LEAF_ONLY("leafOnly"),

    /** Holds the calculated change set and one complete view acquisition. */
    FULL_VIEW("fullView"),

    /** Holds the calculated change set and two complete view acquisitions. */
    REPEATED_ACQUIRE("repeatedAcquire"),

    /** Holds the calculated change set and both the complete and the leaf view acquisition. */
    CALCULATE_AND_LEAF("calculateAndLeaf");

    /** Stable token of the scenario on the command line and in the report. */
    private final String commandLineName;

    /**
     * Creates one scenario with its stable command line token.
     *
     * @param commandLineName the stable command line token of the scenario
     */
    RetainedMemoryScenario(final String commandLineName) {
        this.commandLineName = commandLineName;
    }

    /**
     * Resolves the scenario carried by a command line token.
     *
     * @param commandLineName the command line token of the requested scenario
     * @return the scenario carrying the token
     * @throws NullPointerException     if the token is null
     * @throws IllegalArgumentException if no scenario carries the token
     */
    public static RetainedMemoryScenario fromCommandLineName(final String commandLineName) {
        Objects.requireNonNull(commandLineName, "commandLineName");
        for (final RetainedMemoryScenario scenario : values()) {
            if (scenario.commandLineName.equals(commandLineName)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario: " + commandLineName);
    }

    /**
     * Returns the stable command line token of this scenario.
     *
     * @return the command line token
     */
    public String commandLineName() {
        return this.commandLineName;
    }

    /**
     * Returns the results this scenario holds after one change detection cycle, in the order the probe
     * acquires them. The calculated change set always leads; the following entries are the view
     * acquisitions the scenario adds, and a repeated entry means the probe acquires that view again.
     *
     * @return the held results of this scenario, never empty
     */
    public List<ResultView> heldResultViews() {
        return switch (this) {
            case CALCULATE_ONLY -> List.of(ResultView.CALCULATED_SET);
            case LEAF_ONLY -> List.of(ResultView.CALCULATED_SET, ResultView.LEAF_VIEW);
            case FULL_VIEW -> List.of(ResultView.CALCULATED_SET, ResultView.FULL_VIEW);
            case REPEATED_ACQUIRE -> List.of(ResultView.CALCULATED_SET, ResultView.FULL_VIEW, ResultView.FULL_VIEW);
            case CALCULATE_AND_LEAF ->
                    List.of(ResultView.CALCULATED_SET, ResultView.FULL_VIEW, ResultView.LEAF_VIEW);
        };
    }

    /**
     * One result a scenario holds after a change detection cycle.
     */
    public enum ResultView {

        /** The change set the cycle calculated. */
        CALCULATED_SET("calculatedSet"),

        /** One complete view acquisition of the calculated change set. */
        FULL_VIEW("fullView"),

        /** One leaf view acquisition of the calculated change set. */
        LEAF_VIEW("leafView");

        /** Stable token of the result view in the report. */
        private final String token;

        /**
         * Creates one result view with its stable token.
         *
         * @param token the stable token of the result view
         */
        ResultView(final String token) {
            this.token = token;
        }

        /**
         * Returns the stable token of this result view.
         *
         * @return the report token
         */
        public String token() {
            return this.token;
        }
    }
}
