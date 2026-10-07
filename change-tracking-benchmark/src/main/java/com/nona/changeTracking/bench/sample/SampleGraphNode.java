package com.nona.changeTracking.bench.sample;

/**
 * Shared and cyclic graph sample node of the frozen sample family.
 * <p>
 * The three dimensional {@link SampleShape} descriptor cannot express graph topology (a shared
 * reference or a cycle needs two references to the same instance), so the graph shapes live next to
 * the rest of the sample family: this node type is a construction contract of the family, every
 * graph sample is built by {@link SampleFamily} and never by a benchmark class.
 * <p>
 * {@link SampleGraphBranch} carries two references and therefore expresses sharing (both references
 * hold the same instance) as well as plain branching (two distinct instances);
 * {@link SampleGraphCycleNode} closes a cycle; {@link SampleGraphLeaf} is the explicit terminal of a
 * shared or plain graph. No field is null: the terminal is an explicit leaf and a cycle closes on an
 * existing node.
 */
public sealed interface SampleGraphNode permits SampleGraphLeaf, SampleGraphBranch, SampleGraphCycleNode {
}
