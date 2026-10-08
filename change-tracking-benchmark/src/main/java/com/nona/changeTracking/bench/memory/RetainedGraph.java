package com.nona.changeTracking.bench.memory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reachability walker that measures the retained footprint of one or more held result roots.
 * <p>
 * The walker starts from the given roots, visits every object reachable through instance fields and
 * reference typed array elements, and sums the {@link ObjectLayout} shallow size of each distinct
 * object. Identity is tracked in an identity map, so a shared subgraph is counted once and a cycle
 * terminates instead of recursing forever; a null field, a null array element and a repeated root
 * contribute nothing. Class objects and class loaders are counted as leaves and never traversed, so
 * the measurement stays inside the held result instead of walking the class metadata universe. The
 * leaf cut is a defensive approximation: the size added for such a leaf is the {@link ObjectLayout}
 * estimate over its declared fields, while the real size of a {@code Class} or {@code ClassLoader}
 * instance is fixed by the VM instance layout rather than by the sum of its declared Java fields.
 * Because those leaves are never expanded, the approximation stays bounded to the two leaf kinds and
 * the graph below them is never spilled into the measured result.
 * <p>
 * The walker reads reference field values through reflection, so the measured JVM has to open the
 * packages of the JDK types the result graph reaches. The real result graph of the framework holds
 * {@code java.lang.String} payloads and {@code java.util} collection storage, therefore the probe is
 * run with {@code --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED}.
 * A package the walker cannot open is reported as an {@link IllegalStateException} naming the field,
 * never silently skipped, because a silent skip would under report the retained footprint.
 */
public final class RetainedGraph {

    /**
     * Private constructor: this class is a static measurement entry point.
     */
    private RetainedGraph() {
    }

    /**
     * Measures the retained footprint of the union of the given result roots: the distinct objects
     * reachable from any root through instance fields and reference array elements, the summed
     * shallow size of those objects and the per class aggregation ordered by descending bytes.
     *
     * @param roots the held result roots, never null, null elements are ignored
     * @return the retained footprint of the reachable set
     * @throws NullPointerException  if roots is null
     * @throws IllegalStateException if a reference field of the reachable set cannot be read
     */
    public static RetainedFootprint measure(final Object... roots) {
        Objects.requireNonNull(roots, "roots");
        final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        final Deque<Object> pending = new ArrayDeque<>();
        final Map<String, long[]> aggregation = new HashMap<>();
        long retainedBytes = 0L;
        for (final Object root : roots) {
            if (root != null) {
                pending.push(root);
            }
        }
        while (!pending.isEmpty()) {
            final Object object = pending.pop();
            if (object == null || visited.containsKey(object)) {
                continue;
            }
            visited.put(object, Boolean.TRUE);
            final long shallow = ObjectLayout.shallowSize(object);
            retainedBytes += shallow;
            final long[] classAggregate = aggregation.computeIfAbsent(object.getClass().getName(), key -> new long[2]);
            classAggregate[0]++;
            classAggregate[1] += shallow;
            if (object instanceof Class || object instanceof ClassLoader) {
                continue;
            }
            if (object.getClass().isArray()) {
                pushArrayElements(object, pending);
                continue;
            }
            pushFieldReferences(object, pending);
        }
        return new RetainedFootprint(retainedBytes, visited.size(), classFootprints(aggregation));
    }

    /**
     * Pushes the non null elements of a reference array onto the pending stack.
     *
     * @param array   the reference array to expand
     * @param pending the pending stack of the walk
     */
    private static void pushArrayElements(final Object array, final Deque<Object> pending) {
        if (array.getClass().getComponentType().isPrimitive()) {
            return;
        }
        final int length = Array.getLength(array);
        for (int index = 0; index < length; index++) {
            final Object element = Array.get(array, index);
            if (element != null) {
                pending.push(element);
            }
        }
    }

    /**
     * Pushes the non null reference field values of an instance, walking its class hierarchy, onto the
     * pending stack.
     *
     * @param object  the instance to expand
     * @param pending the pending stack of the walk
     * @throws IllegalStateException if a reference field cannot be read
     */
    private static void pushFieldReferences(final Object object, final Deque<Object> pending) {
        for (Class<?> current = object.getClass(); current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (final Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                final Object value = readReference(field, object);
                if (value != null) {
                    pending.push(value);
                }
            }
        }
    }

    /**
     * Reads one reference field value, reporting an unreachable module as a loud failure.
     *
     * @param field the reference field to read
     * @param owner the instance owning the field
     * @return the current field value, possibly null
     * @throws IllegalStateException if the field cannot be made accessible or read
     */
    private static Object readReference(final Field field, final Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (final InaccessibleObjectException e) {
            throw new IllegalStateException("Cannot read the reference field " + field
                    + "; the measured JVM must open its module, for example with"
                    + " --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED", e);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException("Cannot read the reference field " + field, e);
        }
    }

    /**
     * Builds the per class aggregation ordered by descending bytes, ties by descending object count,
     * then by class name.
     *
     * @param aggregation className to {object count, byte total} entries
     * @return the ordered class footprints
     */
    private static List<ClassFootprint> classFootprints(final Map<String, long[]> aggregation) {
        return aggregation.entrySet().stream()
                .map(entry -> new ClassFootprint(entry.getKey(), (int) entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(ClassFootprint::bytes).reversed()
                        .thenComparing(Comparator.comparingInt(ClassFootprint::objectCount).reversed())
                        .thenComparing(ClassFootprint::className))
                .toList();
    }
}
