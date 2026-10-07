package com.nona.changeTracking.bench.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
    CALCULATE_ONLY("calculateOnly", "仅计算"),

    /** Holds the calculated change set and one leaf view acquisition. */
    LEAF_ONLY("leafOnly", "仅叶子"),

    /** Holds the calculated change set and one complete view acquisition. */
    FULL_VIEW("fullView", "完整视图"),

    /** Holds the calculated change set and two complete view acquisitions. */
    REPEATED_ACQUIRE("repeatedAcquire", "重复获取"),

    /** Holds the calculated change set and both the complete and the leaf view acquisition. */
    CALCULATE_AND_LEAF("calculateAndLeaf", "计算加叶子");

    /** Logger of the scenario enumeration. */
    private static final Logger log = LoggerFactory.getLogger(RetainedMemoryScenario.class);

    /** Stable token of the scenario on the command line and in the report. */
    private final String commandLineName;

    /** Human readable name of the scenario used in the report header. */
    private final String displayName;

    /**
     * Creates one scenario with its stable command line token and its human readable name.
     *
     * @param commandLineName the stable command line token of the scenario
     * @param displayName     the human readable name of the scenario
     */
    RetainedMemoryScenario(final String commandLineName, final String displayName) {
        this.commandLineName = commandLineName;
        this.displayName = displayName;
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
     * Returns the human readable name of this scenario.
     *
     * @return the display name
     */
    public String displayName() {
        return this.displayName;
    }

    /**
     * Returns the results this scenario holds after one change detection cycle, in the order the probe
     * acquires them. The calculated change set always leads; the following entries are the view
     * acquisitions the scenario adds, and a repeated entry means the probe acquires that view again.
     *
     * @return the held results of this scenario, never empty
     */
    public List<ResultView> heldResultViews() {
        log.error("[red] RetainedMemoryScenario.heldResultViews not implemented");
        throw new UnsupportedOperationException("RetainedMemoryScenario.heldResultViews is not implemented yet");
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
        log.error("[red] RetainedMemoryScenario.fromCommandLineName not implemented");
        throw new UnsupportedOperationException("RetainedMemoryScenario.fromCommandLineName is not implemented yet");
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
