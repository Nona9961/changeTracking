package com.nona.changeTracking.bench.sample;

/**
 * Branch element of a shared or plain graph sample: it carries one layer value and two references.
 * <p>
 * Both references holding the <b>same</b> instance express a shared subgraph (the shape the
 * comparison reuse is measured on); two distinct instances express plain branching (the regression
 * shape without sharing). Fields are package visible on purpose: {@link SampleFamily} constructs
 * every sample and {@link SampleMutator} mutates layer values in place.
 */
public final class SampleGraphBranch implements SampleGraphNode {

    /** Layer value of this branch, the payload compared as a primitive value leaf. */
    String layerValue;

    /** First reference of this branch, never null. */
    SampleGraphNode first;

    /** Second reference of this branch, never null; equals {@link #first} for a shared subgraph. */
    SampleGraphNode second;

    /**
     * Creates a branch; {@link SampleFamily} owns every construction call.
     *
     * @param layerValue layer value of the branch
     * @param first      first reference, never null
     * @param second     second reference, never null
     */
    SampleGraphBranch(final String layerValue, final SampleGraphNode first, final SampleGraphNode second) {
        this.layerValue = layerValue;
        this.first = first;
        this.second = second;
    }
}
