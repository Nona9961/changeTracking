package com.nona.changeTracking.bench.memory;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Runs one retained memory scenario on the frozen sample family and reports the footprint the held
 * result keeps alive.
 * <p>
 * <b>Load.</b> The scenario consumes the frozen deep chain {@link SampleShape#deepChain()} and the
 * frozen deepest leaf mutation {@link SampleMutator#changeDeepestLeafField(Object)}, the same load the
 * view projection carriers of the change benchmark measure. The cycle is the documented one: build
 * the sample through {@link SampleFamily#create(SampleShape)}, assemble the tracker through
 * {@link ChangeTrackerFactory}, track the sample, apply the frozen change and calculate the change
 * set.
 * <p>
 * <b>Holding scope.</b> A scenario holds the calculated change set plus the view acquisitions its
 * {@link RetainedMemoryScenario#heldResultViews()} declares, so the retention of a repeated view
 * acquisition is measured separately from a repeated calculation, and the calculated change set is
 * the common baseline of every scenario. The results are held outside any measurement region: the
 * probe never runs inside a benchmark loop, it holds the result and measures the reachable set, so
 * the reported figure is the retained footprint and never an allocation or a time figure.
 * <p>
 * <b>Two side source compatibility.</b> The measurement consumes the public framework surface the old
 * and the new build share - {@link ChangeTrackerFactory}, {@link ChangeTracker}, {@link ChangeSet}
 * and its {@code getAllChanges} / {@code getLeafChanges} entries - and never names an
 * {@code ObjectChange} component, a removed node type or a new result type, so one source builds and
 * runs against both the old {@code ChangeNode} model and the unified result model.
 */
public final class RetainedMemoryMeasurement {

    /** Logger of the measurement. */
    private static final Logger log = LoggerFactory.getLogger(RetainedMemoryMeasurement.class);

    /** Name of the frozen load shape of every scenario. */
    public static final String FROZEN_SHAPE_NAME = "deepChain";

    /**
     * Private constructor: this class is a static measurement entry point.
     */
    private RetainedMemoryMeasurement() {
    }

    /**
     * Measures the retained footprint of one scenario on the frozen deep chain load: assemble the
     * tracked sample, apply the frozen change, calculate the change set, hold the results the
     * scenario declares and measure their reachable set.
     *
     * @param scenario the retention scenario to measure, never null
     * @return the retained memory report of the scenario
     * @throws NullPointerException  if the scenario is null
     * @throws IllegalStateException if a reference field of the held result cannot be read
     */
    public static RetainedMemoryReport measure(final RetainedMemoryScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        final ChangeTracker tracker = ChangeTrackerFactory.builder().withDefaults().build();
        final Object sample = SampleFamily.create(SampleShape.deepChain());
        tracker.track(sample);
        SampleMutator.changeDeepestLeafField(sample);
        final ChangeSet changeSet = tracker.calculateChanges();
        final RetainedFootprint footprint = RetainedGraph.measure(heldResultRoots(scenario, changeSet));
        log.info("Measured retained footprint of scenario {} on shape {}: {} bytes in {} objects",
                scenario.commandLineName(), FROZEN_SHAPE_NAME, footprint.retainedBytes(), footprint.objectCount());
        return new RetainedMemoryReport(scenario, FROZEN_SHAPE_NAME, footprint);
    }

    /**
     * Acquires the results the scenario holds, in the order the scenario declares them: the calculated
     * change set itself, a fresh complete view acquisition per complete view entry and a fresh leaf
     * view acquisition per leaf view entry.
     *
     * @param scenario  the retention scenario declaring the held results
     * @param changeSet the calculated change set the views are acquired from
     * @return the held result roots, in acquisition order
     */
    private static Object[] heldResultRoots(final RetainedMemoryScenario scenario, final ChangeSet changeSet) {
        final List<RetainedMemoryScenario.ResultView> heldViews = scenario.heldResultViews();
        final Object[] roots = new Object[heldViews.size()];
        for (int index = 0; index < heldViews.size(); index++) {
            roots[index] = switch (heldViews.get(index)) {
                case CALCULATED_SET -> changeSet;
                case FULL_VIEW -> changeSet.getAllChanges();
                case LEAF_VIEW -> changeSet.getLeafChanges();
            };
        }
        return roots;
    }
}
