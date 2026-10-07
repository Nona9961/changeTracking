package com.nona.changeTracking.bench.memory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Shallow size estimator of the HotSpot 64 bit object layout used by the retained memory probe.
 * <p>
 * The retained memory probe estimates the footprint a held result graph keeps alive by summing the
 * shallow size of every reachable object; this class is the layout kernel of that estimate. It models
 * the layout the measured JVM actually uses: a 12 byte object header (8 byte mark word plus a 4 byte
 * compressed class pointer), a 16 byte array header (mark word, class pointer and the 4 byte length),
 * 4 byte compressed ordinary object pointers, and an 8 byte object alignment. Instance fields are
 * summed over the declared fields of the class and its superclasses (static fields are never part of
 * an instance), and the sum is rounded up to the alignment; HotSpot packs fields of descending size
 * without interior gaps, so the sum is the shallow size for the field layouts this probe sees.
 * <p>
 * The estimator reads the field layout of the JVM under test instead of hard coding the field set of
 * any class, so it follows a JDK that adds or removes a field (for example the {@code hashIsZero}
 * flag of {@code java.lang.String}). The rules are validated against exact allocation sizes measured
 * through {@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes} in the probe recorded with
 * this task: the twelve primitive and object array lengths, the empty and mixed field carriers, the
 * inherited field carrier and the {@code java.lang.String} instance all match the estimate exactly.
 * <p>
 * The estimator is read only over field metadata: it never reads a field value and never makes a
 * field accessible, so it works without opening any module.
 */
public final class ObjectLayout {

    /** Object header of the measured layout: 8 byte mark word plus a 4 byte compressed class pointer. */
    public static final int OBJECT_HEADER_BYTES = 12;

    /** Array header of the measured layout: mark word, compressed class pointer and the 4 byte length. */
    public static final int ARRAY_HEADER_BYTES = 16;

    /** Object alignment of the measured layout. */
    public static final int OBJECT_ALIGNMENT_BYTES = 8;

    /** Size of a compressed ordinary object pointer (a field or an array element of reference type). */
    public static final int COMPRESSED_REFERENCE_BYTES = 4;

    /**
     * Private constructor: this class is a static layout entry point.
     */
    private ObjectLayout() {
    }

    /**
     * Returns the shallow size of an instance, dispatching to the array or the instance layout.
     *
     * @param instance the instance to measure, never null
     * @return the shallow size of the instance in bytes
     * @throws NullPointerException     if instance is null
     * @throws IllegalArgumentException if the instance is not measurable by this estimator
     */
    public static long shallowSize(final Object instance) {
        Objects.requireNonNull(instance, "instance");
        final Class<?> type = instance.getClass();
        if (type.isArray()) {
            return shallowSizeOfArray(instance);
        }
        return shallowSizeOfClass(type);
    }

    /**
     * Returns the shallow size of a non array instance of the given class: the header plus the summed
     * instance fields of the class and its superclasses, rounded up to the object alignment.
     *
     * @param type the class of the instance to measure, never null
     * @return the shallow size of an instance of the class in bytes
     * @throws NullPointerException     if type is null
     * @throws IllegalArgumentException if the class is primitive or an array class
     */
    public static long shallowSizeOfClass(final Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive() || type.isArray()) {
            throw new IllegalArgumentException("Not an instance class: " + type.getName());
        }
        return align(OBJECT_HEADER_BYTES + instanceFieldBytes(type));
    }

    /**
     * Returns the shallow size of an array instance: the array header plus one element slot per
     * element, each slot sized by the component type, rounded up to the object alignment.
     *
     * @param array the array to measure, never null
     * @return the shallow size of the array in bytes
     * @throws NullPointerException     if array is null
     * @throws IllegalArgumentException if the value is not an array
     */
    public static long shallowSizeOfArray(final Object array) {
        Objects.requireNonNull(array, "array");
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("Not an array: " + array.getClass().getName());
        }
        final Class<?> componentType = array.getClass().getComponentType();
        return align(ARRAY_HEADER_BYTES + (long) arrayElementBytes(componentType) * Array.getLength(array));
    }

    /**
     * Sums the instance fields declared by the class and every superclass up to {@link Object}.
     *
     * @param type the non array, non primitive class to sum the instance fields of
     * @return the summed instance field bytes
     */
    private static long instanceFieldBytes(final Class<?> type) {
        long bytes = 0;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (final Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    bytes += fieldBytes(field.getType());
                }
            }
        }
        return bytes;
    }

    /**
     * Returns the number of bytes a field of the given type occupies in the measured layout.
     *
     * @param fieldType the field type
     * @return the field size in bytes
     */
    private static int fieldBytes(final Class<?> fieldType) {
        if (fieldType.isPrimitive()) {
            return primitiveBytes(fieldType);
        }
        return COMPRESSED_REFERENCE_BYTES;
    }

    /**
     * Returns the number of bytes one array element of the given component type occupies.
     *
     * @param componentType the array component type
     * @return the element size in bytes
     */
    private static int arrayElementBytes(final Class<?> componentType) {
        if (componentType.isPrimitive()) {
            return primitiveBytes(componentType);
        }
        return COMPRESSED_REFERENCE_BYTES;
    }

    /**
     * Returns the width of a primitive type in the measured layout.
     *
     * @param primitiveType a primitive type
     * @return the width of the primitive type in bytes
     */
    private static int primitiveBytes(final Class<?> primitiveType) {
        if (primitiveType == boolean.class || primitiveType == byte.class) {
            return 1;
        }
        if (primitiveType == char.class || primitiveType == short.class) {
            return 2;
        }
        if (primitiveType == int.class || primitiveType == float.class) {
            return 4;
        }
        return 8;
    }

    /**
     * Rounds a byte count up to the object alignment.
     *
     * @param bytes the byte count
     * @return the aligned byte count
     */
    private static long align(final long bytes) {
        return (bytes + OBJECT_ALIGNMENT_BYTES - 1) / OBJECT_ALIGNMENT_BYTES * OBJECT_ALIGNMENT_BYTES;
    }
}
