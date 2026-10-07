package com.nona.changeTracking.bench.sample;

/**
 * Terminal element of a shared or plain graph sample: an explicit leaf carrying one layer value and
 * no further reference, so a graph sample never uses a null reference as its terminator.
 * <p>
 * Fields are package visible on purpose: {@link SampleFamily} constructs every sample and
 * {@link SampleMutator} mutates layer values in place; no consumer builds a sample of its own.
 */
public final class SampleGraphLeaf implements SampleGraphNode {

    /** Layer value of this leaf, the payload compared as a primitive value leaf. */
    String layerValue;

    /**
     * Creates a terminal leaf; {@link SampleFamily} owns every construction call.
     *
     * @param layerValue layer value of the leaf
     */
    SampleGraphLeaf(final String layerValue) {
        this.layerValue = layerValue;
    }
}
