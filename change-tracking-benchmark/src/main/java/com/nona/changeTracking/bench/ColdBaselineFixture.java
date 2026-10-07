package com.nona.changeTracking.bench;

import com.nona.changeTracking.bench.sample.SampleOrder;
import com.nona.changeTracking.bench.sample.SampleOrderSummary;
import com.nona.changeTracking.domain.model.snapshot.ArrayNode;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.tracking.BaselineSnapshot;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the tracking baseline of a benchmark sample by hand, without calling the target snapshot
 * strategy and without touching either type processing cache.
 * <p>
 * <b>Why a fixture instead of the strategy.</b> The first use protocol of the type processing caches
 * measures the first target operation of a fork, so its preparation must not warm the caches it
 * measures: the frozen protocol therefore builds the baseline of the corresponding load from a named fixture
 * instead of calling {@code track}, {@code createSnapshot} or either cache. This fixture is that
 * construction point: it reads the sample fields directly through plain reflection and builds the
 * existing node types, so the prepared fork still finds cold caches.
 * <p>
 * <b>Correspondence to the default capability.</b> The fixture mirrors the unconfigured default
 * capability for the frozen sample family: fields are read subclass first and in declaration order,
 * value types are represented as {@code PrimitiveNode}, collections as {@code CollectionNode} and
 * complex objects as {@code ObjectNode}, and identifiers of collection items fall back to
 * {@code System.identityHashCode} because no extractor is registered. Samples of a type outside the
 * frozen sample family, or samples built with a configured capability, must not be handed to it.
 * <p>
 * <b>Equivalence.</b> The fixture's node tree is compared against a real snapshot of the same sample
 * by an independent verification run; the fixture itself never calls the snapshot strategy, so a
 * mismatch is a fixture defect and not a cache state effect.
 * <p>
 * The preparation of this fixture is not a whole library and JVM cold start: it warms the shared node
 * types and the sample family, so the recorded state is the target cache being cold, exactly as the
 * frozen protocol prescribes.
 */
public final class ColdBaselineFixture {

    /**
     * Default value type packages, identical to the unconfigured default capability snapshot strategy;
     * this fixture never calls that strategy, so the set is restated here.
     */
    private static final Set<String> DEFAULT_VALUE_PACKAGES = Set.of(
            "java.time",
            "java.math",
            "java.net");

    /**
     * Default value type classes, identical to the unconfigured default capability snapshot strategy;
     * this fixture never calls that strategy, so the set is restated here.
     */
    private static final Set<Class<?>> DEFAULT_VALUE_CLASSES = Set.of(
            UUID.class,
            Locale.class,
            Currency.class,
            Pattern.class,
            File.class,
            Path.class);

    /**
     * Wrapper classes of the primitive types, identical to the unconfigured default capability snapshot
     * strategy.
     */
    private static final Set<Class<?>> WRAPPER_TYPES = Set.of(
            Boolean.class,
            Character.class,
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            Void.class);

    /**
     * Private constructor: this class is a static construction entry point.
     */
    private ColdBaselineFixture() {
    }

    /**
     * Builds the baseline of the given sample as a {@link BaselineSnapshot} holding the sample mapped
     * to its hand built node tree.
     * <p>
     * The returned baseline can be registered on a tracker with
     * {@code ChangeTracker.fromBaseline(capability, baseline)}; because the fixture does not traverse
     * the target strategy, registering it does not warm the measured caches either.
     *
     * @param sample a fully populated sample root created by the frozen sample family
     * @return the baseline snapshot of the sample, equivalent to a default capability snapshot
     * @throws NullPointerException     if sample is null
     * @throws IllegalArgumentException if the sample tree holds a type the fixture does not support
     */
    public static BaselineSnapshot baselineOf(final Object sample) {
        Objects.requireNonNull(sample, "sample");
        if (!isSampleRoot(sample)) {
            throw new IllegalArgumentException("Unsupported sample type: " + sample.getClass().getName());
        }
        final Map<Object, ValueNode> entities = new IdentityHashMap<>();
        entities.put(sample, build(sample, new IdentityHashMap<>()));
        return new BaselineSnapshot(entities);
    }

    /**
     * Tells whether the sample root belongs to the frozen sample family.
     *
     * @param sample the sample root to check
     * @return true when the type is a sample root of this family
     */
    private static boolean isSampleRoot(final Object sample) {
        return sample instanceof SampleOrder || sample instanceof SampleOrderSummary;
    }

    /**
     * Builds the node of one value, mirroring the unconfigured default capability: value types become
     * {@link PrimitiveNode}, value arrays an {@link ArrayNode} (defensive copy), complex object arrays
     * and collections recursively a {@link CollectionNode}, maps a {@link CollectionNode} of key/value
     * entries, and every other complex object an {@link ObjectNode} identified by
     * {@link System#identityHashCode(Object)}.
     *
     * @param value   the value to represent
     * @param visited the identity map of already built nodes, used for cycles and shared references
     * @return the node of the value
     */
    private static ValueNode build(final Object value, final Map<Object, ValueNode> visited) {
        if (value == null) {
            return new NullNode();
        }
        final ValueNode known = visited.get(value);
        if (known != null) {
            return known;
        }
        final Class<?> type = value.getClass();
        if (isValueType(type)) {
            return new PrimitiveNode(value);
        }
        if (type.isArray()) {
            return buildArray(value, visited);
        }
        if (value instanceof Collection<?> collection) {
            final List<ValueNode> items = new ArrayList<>(collection.size());
            final CollectionNode node = new CollectionNode(items);
            visited.put(value, node);
            for (final Object item : collection) {
                items.add(build(item, visited));
            }
            return node;
        }
        if (value instanceof Map<?, ?> map) {
            final List<ValueNode> items = new ArrayList<>(map.size());
            final CollectionNode node = new CollectionNode(items);
            visited.put(value, node);
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                items.add(buildMapEntry(entry, visited));
            }
            return node;
        }
        return buildComplexObject(value, visited);
    }

    /**
     * Builds the node of one array: a value array mirrors the strategy's defensive copy into an
     * {@link ArrayNode}; an array of complex objects is represented as a {@link CollectionNode}, like
     * every other ordered collection.
     *
     * @param array   the array to represent
     * @param visited the identity map of already built nodes
     * @return the node of the array
     */
    private static ValueNode buildArray(final Object array, final Map<Object, ValueNode> visited) {
        if (isValueArray(array.getClass())) {
            return new ArrayNode(deepCopyArray(array));
        }
        final int length = Array.getLength(array);
        final List<ValueNode> items = new ArrayList<>(length);
        final CollectionNode node = new CollectionNode(items);
        visited.put(array, node);
        for (int index = 0; index < length; index++) {
            items.add(build(Array.get(array, index), visited));
        }
        return node;
    }

    /**
     * Builds the node of one map entry: the entry key and value under the field names the snapshot
     * strategy uses, with the key as the entry identifier.
     *
     * @param entry   the map entry to represent
     * @param visited the identity map of already built nodes
     * @return the object node of the entry
     */
    private static ObjectNode buildMapEntry(final Map.Entry<?, ?> entry, final Map<Object, ValueNode> visited) {
        final Map<String, ValueNode> fields = new HashMap<>();
        fields.put("key", build(entry.getKey(), visited));
        fields.put("value", build(entry.getValue(), visited));
        return new ObjectNode(fields, entry.getKey());
    }

    /**
     * Builds the node of one complex object: non static fields of the class chain in declaration order
     * (subclass first), the first occurrence of a hidden field name winning, and the identity hash of
     * the object as identifier.
     *
     * @param value   the complex object to represent
     * @param visited the identity map of already built nodes
     * @return the object node of the value
     */
    private static ObjectNode buildComplexObject(final Object value, final Map<Object, ValueNode> visited) {
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        final ObjectNode node = new ObjectNode(fields, System.identityHashCode(value));
        visited.put(value, node);
        for (final Field field : fieldsOf(value.getClass())) {
            field.setAccessible(true);
            final Object fieldValue;
            try {
                fieldValue = field.get(value);
            } catch (final IllegalAccessException e) {
                throw new IllegalStateException("Failed to access field: " + field.getName(), e);
            }
            fields.putIfAbsent(field.getName(), build(fieldValue, visited));
        }
        return node;
    }

    /**
     * Collects the non static fields of a class chain: subclass first, declaration order inside each
     * class, stopping before {@link Object}.
     *
     * @param type the class to collect
     * @return the ordered non static fields
     */
    private static List<Field> fieldsOf(final Class<?> type) {
        final List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (final Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * Tells whether the type is a value type of the unconfigured default capability.
     *
     * @param type the type to classify
     * @return true when the default capability represents the type as a primitive node
     */
    private static boolean isValueType(final Class<?> type) {
        if (type.isPrimitive() || WRAPPER_TYPES.contains(type)) {
            return true;
        }
        if (type == String.class) {
            return true;
        }
        if (type.isEnum()) {
            return true;
        }
        final String packageName = type.getPackageName();
        return DEFAULT_VALUE_PACKAGES.contains(packageName) || DEFAULT_VALUE_CLASSES.contains(type);
    }

    /**
     * Tells whether the array type is a value array of the unconfigured default capability: the
     * component chain is followed to its bottom, which has to be primitive or a value type.
     *
     * @param arrayType the array type to classify
     * @return true when the default capability represents the array as an array node
     */
    private static boolean isValueArray(final Class<?> arrayType) {
        Class<?> component = arrayType.getComponentType();
        while (component.isArray()) {
            component = component.getComponentType();
        }
        return component.isPrimitive() || isValueType(component);
    }

    /**
     * Defensive copy of an array, mirroring the strategy: one dimensional arrays are copied by
     * component type, multi dimensional arrays are copied recursively row by row.
     *
     * @param array the source array
     * @return a copy sharing no element reference with the source for value arrays
     */
    private static Object deepCopyArray(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        final int length = Array.getLength(array);
        if (componentType.isArray()) {
            final Object copy = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, deepCopyArray(Array.get(array, index)));
            }
            return copy;
        }
        final Object copy = Array.newInstance(componentType, length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }
}