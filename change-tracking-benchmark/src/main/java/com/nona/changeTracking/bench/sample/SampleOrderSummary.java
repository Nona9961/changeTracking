package com.nona.changeTracking.bench.sample;

import java.util.List;

/**
 * Narrow benchmark sample root: {@value SampleShape#SUPPORTED_FIELD_COUNT_LOW} scalar fields,
 * one nested address chain and one line item collection.
 * <p>
 * Fields are package visible on purpose: the snapshot strategy of
 * {@code change-tracking-core} reads fields reflectively and {@link SampleMutator}
 * mutates them for the change detection benchmarks.
 */
public final class SampleOrderSummary {

    String orderNumber;
    String status;
    String currency;
    String customerName;
    String remark;
    SampleAddress address;
    List<SampleLineItem> items;

    /**
     * Creates an empty narrow sample; {@link SampleFamily} fills every field.
     */
    public SampleOrderSummary() {
    }

    /**
     * Returns the nested address chain head.
     *
     * @return the nested address chain head
     */
    public SampleAddress address() {
        return address;
    }

    /**
     * Returns the line item collection.
     *
     * @return the line item collection, never null
     */
    public List<SampleLineItem> items() {
        return items;
    }
}