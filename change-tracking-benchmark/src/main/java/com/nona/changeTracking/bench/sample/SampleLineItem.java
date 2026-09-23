package com.nona.changeTracking.bench.sample;

/**
 * Mutable line item of the benchmark sample collection.
 * <p>
 * The identifier field allows change detection to match items by business identity,
 * so value replacement, addition, removal and reordering are distinguishable.
 */
public final class SampleLineItem {

    Long id;
    String sku;
    int quantity;
    long unitPriceCents;

    /**
     * Creates an empty line item; {@link SampleFamily} fills every field.
     */
    public SampleLineItem() {
    }

    /**
     * Returns the business identifier of this item.
     *
     * @return the item identifier
     */
    public Long id() {
        return id;
    }

    /**
     * Returns the stock keeping unit of this item.
     *
     * @return the stock keeping unit
     */
    public String sku() {
        return sku;
    }

    /**
     * Returns the quantity of this item.
     *
     * @return the quantity
     */
    public int quantity() {
        return quantity;
    }

    /**
     * Returns the unit price in cents of this item.
     *
     * @return the unit price in cents
     */
    public long unitPriceCents() {
        return unitPriceCents;
    }
}