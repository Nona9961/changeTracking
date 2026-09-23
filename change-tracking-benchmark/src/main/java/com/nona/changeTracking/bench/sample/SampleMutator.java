package com.nona.changeTracking.bench.sample;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;

/**
 * Applies the mutation operations of the load sample family: property changes and the
 * three collection shapes (value replacement, add or remove, reorder).
 * <p>
 * Mutation is expressed as domain operations on the sample root, not as raw reflection
 * from the benchmark classes, so every benchmark task mutates samples the same way.
 */
public final class SampleMutator {

    /** Suffix appended to string payloads so a mutated value always differs from the original. */
    private static final String CHANGED_SUFFIX = "-changed";

    /**
     * Private constructor: this class is a static mutation entry point.
     */
    private SampleMutator() {
    }

    /**
     * Changes one scalar field of the sample.
     *
     * @param sample    a sample root created by {@link SampleFamily#create(SampleShape)}
     * @param fieldName name of the scalar field to change
     * @throws NullPointerException     if sample or fieldName is null
     * @throws IllegalArgumentException if the sample type is unsupported or the field is unknown or structural
     */
    public static void changeField(final Object sample, final String fieldName) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(fieldName, "fieldName");
        final Field field = scalarField(sample, fieldName);
        write(field, sample, changedValue(read(field, sample)));
    }

    /**
     * Changes every scalar field of the sample (full change ratio).
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample type is unsupported
     */
    public static void changeAllFields(final Object sample) {
        Objects.requireNonNull(sample, "sample");
        requireSupported(sample);
        for (final Field field : sample.getClass().getDeclaredFields()) {
            if (isScalarField(field)) {
                write(field, sample, changedValue(read(field, sample)));
            }
        }
    }

    /**
     * Replaces the payload of one item while keeping its identifier (value replacement shape).
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @param index  index of the item to replace
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample type is unsupported or the index is out of range
     */
    public static void replaceItem(final Object sample, final int index) {
        Objects.requireNonNull(sample, "sample");
        final SampleLineItem item = itemAt(sample, index);
        item.sku = item.sku + CHANGED_SUFFIX;
        item.quantity = item.quantity + 1;
        item.unitPriceCents = item.unitPriceCents + 1L;
    }

    /**
     * Appends one item with a fresh identifier (addition shape).
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample type is unsupported
     */
    public static void addItem(final Object sample) {
        Objects.requireNonNull(sample, "sample");
        final List<SampleLineItem> items = itemsOf(sample);
        long nextId = 0L;
        for (final SampleLineItem item : items) {
            nextId = Math.max(nextId, item.id + 1L);
        }
        items.add(SampleFamily.createItem(nextId));
    }

    /**
     * Removes one item (removal shape).
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @param index  index of the item to remove
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample type is unsupported or the index is out of range
     */
    public static void removeItem(final Object sample, final int index) {
        Objects.requireNonNull(sample, "sample");
        final List<SampleLineItem> items = itemsOf(sample);
        requireValidIndex(index, items.size());
        items.remove(index);
    }

    /**
     * Reorders the item collection without changing its elements (reorder shape).
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample type is unsupported
     */
    public static void reorderItems(final Object sample) {
        Objects.requireNonNull(sample, "sample");
        final List<SampleLineItem> items = itemsOf(sample);
        if (items.size() <= 1) {
            return;
        }
        items.add(items.remove(0));
    }

    /**
     * Returns the item at the given index after validating the sample type and the index.
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @param index  index of the requested item
     * @return the item at the given index
     * @throws IllegalArgumentException if the sample type is unsupported or the index is out of range
     */
    private static SampleLineItem itemAt(final Object sample, final int index) {
        final List<SampleLineItem> items = itemsOf(sample);
        requireValidIndex(index, items.size());
        return items.get(index);
    }

    /**
     * Returns the mutable item collection of a supported sample root.
     *
     * @param sample a sample root created by {@link SampleFamily#create(SampleShape)}
     * @return the item collection of the sample
     * @throws IllegalArgumentException if the sample type is unsupported
     */
    private static List<SampleLineItem> itemsOf(final Object sample) {
        requireSupported(sample);
        if (sample instanceof SampleOrder order) {
            return order.items;
        }
        return ((SampleOrderSummary) sample).items;
    }

    /**
     * Resolves a scalar field of a supported sample root.
     *
     * @param sample    a sample root created by {@link SampleFamily#create(SampleShape)}
     * @param fieldName name of the requested field
     * @return the scalar field
     * @throws IllegalArgumentException if the sample type is unsupported or the field is unknown or structural
     */
    private static Field scalarField(final Object sample, final String fieldName) {
        requireSupported(sample);
        final Field field;
        try {
            field = sample.getClass().getDeclaredField(fieldName);
        } catch (final NoSuchFieldException e) {
            throw new IllegalArgumentException("Unknown scalar field: " + fieldName, e);
        }
        if (!isScalarField(field)) {
            throw new IllegalArgumentException("Field is structural and cannot be changed as a scalar: " + fieldName);
        }
        return field;
    }

    /**
     * Verifies that the sample root belongs to the frozen sample family.
     *
     * @param sample the object to verify
     * @throws IllegalArgumentException if the type is not a sample root of this family
     */
    private static void requireSupported(final Object sample) {
        if (!(sample instanceof SampleOrder) && !(sample instanceof SampleOrderSummary)) {
            throw new IllegalArgumentException("Unsupported sample type: " + sample.getClass().getName());
        }
    }

    /**
     * Verifies that the index addresses an existing item.
     *
     * @param index index to verify
     * @param size  current collection size
     * @throws IllegalArgumentException if the index is negative or beyond the collection
     */
    private static void requireValidIndex(final int index, final int size) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("Item index out of range: " + index + ", collection size: " + size);
        }
    }

    /**
     * Returns a value that differs from the given scalar value.
     *
     * @param current the current scalar value
     * @return the mutated value of the same type
     * @throws IllegalArgumentException if the value type is not a supported scalar
     */
    private static Object changedValue(final Object current) {
        if (current == null) {
            throw new IllegalArgumentException("Scalar value must not be null");
        }
        if (current instanceof String text) {
            return text + CHANGED_SUFFIX;
        }
        if (current instanceof Long value) {
            return value + 1L;
        }
        if (current instanceof Integer value) {
            return value + 1;
        }
        if (current instanceof Boolean value) {
            return !value;
        }
        throw new IllegalArgumentException("Unsupported scalar value type: " + current.getClass().getName());
    }

    /**
     * Reads a field of the sample through reflection.
     *
     * @param field  the field to read
     * @param sample the sample owning the field
     * @return the current field value
     */
    private static Object read(final Field field, final Object sample) {
        try {
            field.setAccessible(true);
            return field.get(sample);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Failed to read field: " + field.getName(), e);
        }
    }

    /**
     * Writes a field of the sample through reflection.
     *
     * @param field  the field to write
     * @param sample the sample owning the field
     * @param value  the value to write
     */
    private static void write(final Field field, final Object sample, final Object value) {
        try {
            field.setAccessible(true);
            field.set(sample, value);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Failed to write field: " + field.getName(), e);
        }
    }

    /**
     * Tells whether the field is a mutable scalar field of the sample payload.
     *
     * @param field the field to inspect
     * @return true when the field is non static and carries a scalar payload type
     */
    private static boolean isScalarField(final Field field) {
        if (Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        final Class<?> type = field.getType();
        return type == String.class || type == Long.class || type == Integer.class
                || type == int.class || type == long.class || type == boolean.class;
    }
}