package com.nona.changeTracking.bench.sample;

/**
 * Inner element of the nested address chain.
 *
 * @param city   city name
 * @param street street name
 * @param next   the next chain element, never null; the chain ends with a leaf
 */
public record SampleAddressLink(String city, String street, SampleAddress next) implements SampleAddress {
}
