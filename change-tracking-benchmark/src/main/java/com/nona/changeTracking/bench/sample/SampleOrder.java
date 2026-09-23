package com.nona.changeTracking.bench.sample;

import java.util.List;

/**
 * Wide benchmark sample root: {@value SampleShape#SUPPORTED_FIELD_COUNT_HIGH} scalar fields,
 * one nested address chain and one line item collection.
 * <p>
 * Fields are package visible on purpose: the snapshot strategy of
 * {@code change-tracking-core} reads fields reflectively and {@link SampleMutator}
 * mutates them for the change detection benchmarks.
 */
public final class SampleOrder {

    Long id;
    String orderNumber;
    String status;
    String channel;
    String currency;
    String customerName;
    String customerPhone;
    String shippingCity;
    String shippingStreet;
    String remark;
    String couponCode;
    String source;
    String priority;
    String warehouseCode;
    String carrierCode;
    String paymentMethod;
    String invoiceTitle;
    String contactEmail;
    String trackingNumber;
    String afterSaleFlag;
    SampleAddress address;
    List<SampleLineItem> items;

    /**
     * Creates an empty wide sample; {@link SampleFamily} fills every field.
     */
    public SampleOrder() {
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