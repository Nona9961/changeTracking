package com.nona.changeTracking.bench.sample;

/**
 * Immutable nested address chain of a benchmark sample.
 * <p>
 * The chain is modelled with an explicit leaf instead of a null terminator so the
 * sample never carries a null semantic placeholder.
 */
public sealed interface SampleAddress permits SampleAddressLeaf, SampleAddressLink {

    /**
     * Returns the city of this chain element.
     *
     * @return the city name
     */
    String city();

    /**
     * Returns the street of this chain element.
     *
     * @return the street name
     */
    String street();
}
