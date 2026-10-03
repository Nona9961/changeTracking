package com.nona.changeTracking.bench.sample;

/**
 * Cycle element of a cyclic graph sample: it carries one layer value and one reference, which
 * {@link SampleFamily} closes back into the cycle after construction (an immutable value cannot
 * reference itself, so the closing reference is written by the family).
 * <p>
 * The reference is never null: a cycle closes on an existing node instead of a null terminator.
 * Fields are package visible on purpose: {@link SampleFamily} constructs every sample and
 * {@link SampleMutator} mutates layer values in place.
 */
public final class SampleGraphCycleNode implements SampleGraphNode {

    /** Layer value of this cycle element, the payload compared as a primitive value leaf. */
    String layerValue;

    /** Next element of the cycle, never null; the last element references the head. */
    SampleGraphCycleNode next;

    /**
     * Creates a cycle element without a reference; {@link SampleFamily} closes the cycle afterwards.
     *
     * @param layerValue layer value of the cycle element
     */
    SampleGraphCycleNode(final String layerValue) {
        this.layerValue = layerValue;
    }
}
