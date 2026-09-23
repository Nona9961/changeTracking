package com.nona.changeTracking.bench.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the frozen load sample family from a {@link SampleShape}.
 * <p>
 * This class is the single construction point of benchmark load: the snapshot, diff,
 * facade and end to end benchmark tasks reuse it and never build samples of their own.
 */
public final class SampleFamily {

    /**
     * Private constructor: this class is a static construction entry point.
     */
    private SampleFamily() {
    }

    /**
     * Creates a fully populated sample root for the given shape.
     *
     * @param shape the sample shape, its dimensions are already validated by the {@link SampleShape}
     *              canonical constructor
     * @return a {@link SampleOrder} for {@value SampleShape#SUPPORTED_FIELD_COUNT_HIGH} fields or a
     *         {@link SampleOrderSummary} for {@value SampleShape#SUPPORTED_FIELD_COUNT_LOW} fields
     * @throws NullPointerException if shape is null
     */
    public static Object create(final SampleShape shape) {
        Objects.requireNonNull(shape, "shape");
        if (shape.fieldCount() == SampleShape.SUPPORTED_FIELD_COUNT_LOW) {
            return fillSummary(new SampleOrderSummary(), shape);
        }
        return fillOrder(new SampleOrder(), shape);
    }

    /**
     * Returns the identity extractors needed to track the sample collection by business identity.
     *
     * @return identifier extractors keyed by sample type, ready for the tracking configuration
     */
    public static Map<Class<?>, Function<Object, Object>> identifierExtractors() {
        return Map.of(SampleLineItem.class, item -> ((SampleLineItem) item).id());
    }

    /**
     * Creates one fully populated item with the given business identifier.
     *
     * @param id the unique business identifier of the item
     * @return a line item carrying the identifier, a derived sku, a positive quantity and a positive price
     */
    static SampleLineItem createItem(final long id) {
        final SampleLineItem item = new SampleLineItem();
        item.id = id;
        item.sku = "SKU-" + id;
        item.quantity = 1 + (int) (id % 9);
        item.unitPriceCents = 100L + id;
        return item;
    }

    /**
     * Fills every wide sample field, the address chain and the item collection.
     *
     * @param sample the wide sample to fill
     * @param shape  the shape describing nesting depth and collection size
     * @return the filled sample
     */
    private static SampleOrder fillOrder(final SampleOrder sample, final SampleShape shape) {
        sample.id = 1000L;
        sample.orderNumber = "SO-20260101-0001";
        sample.status = "CREATED";
        sample.channel = "WEB";
        sample.currency = "CNY";
        sample.customerName = "Ada Lovelace";
        sample.customerPhone = "13800000000";
        sample.shippingCity = "Shanghai";
        sample.shippingStreet = "Nanjing Road 1";
        sample.remark = "handle with care";
        sample.couponCode = "COUPON-10";
        sample.source = "MOBILE";
        sample.priority = "NORMAL";
        sample.warehouseCode = "WH-SH-01";
        sample.carrierCode = "SF";
        sample.paymentMethod = "ALIPAY";
        sample.invoiceTitle = "Nona Ltd";
        sample.contactEmail = "contact@example.com";
        sample.trackingNumber = "SF1234567890";
        sample.afterSaleFlag = "N";
        sample.address = createAddress(shape.nestingDepth(), 0);
        sample.items = createItems(shape.collectionSize());
        return sample;
    }

    /**
     * Fills every narrow sample field, the address chain and the item collection.
     *
     * @param sample the narrow sample to fill
     * @param shape  the shape describing nesting depth and collection size
     * @return the filled sample
     */
    private static SampleOrderSummary fillSummary(final SampleOrderSummary sample, final SampleShape shape) {
        sample.orderNumber = "SO-20260101-0001";
        sample.status = "CREATED";
        sample.currency = "CNY";
        sample.customerName = "Ada Lovelace";
        sample.remark = "handle with care";
        sample.address = createAddress(shape.nestingDepth(), 0);
        sample.items = createItems(shape.collectionSize());
        return sample;
    }

    /**
     * Builds the item collection with identifiers starting at zero.
     *
     * @param size the number of items to create
     * @return the item collection, empty when size is zero
     */
    private static List<SampleLineItem> createItems(final int size) {
        final List<SampleLineItem> items = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            items.add(createItem(index));
        }
        return items;
    }

    /**
     * Builds the address chain of the requested depth, ending with an explicit leaf.
     *
     * @param depth the remaining nesting depth, at least 1
     * @param level the zero based level of the element being created
     * @return the chain head
     */
    private static SampleAddress createAddress(final int depth, final int level) {
        final String city = "city-" + level;
        final String street = "street-" + level;
        if (depth == 1) {
            return new SampleAddressLeaf(city, street);
        }
        return new SampleAddressLink(city, street, createAddress(depth - 1, level + 1));
    }
}