package com.nona.changeTracking.bench.sample;

/**
 * Terminal element of the nested address chain: a leaf without further nesting.
 *
 * @param city   city name
 * @param street street name
 */
public record SampleAddressLeaf(String city, String street) implements SampleAddress {
}
