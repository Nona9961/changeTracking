package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import com.nona.changeTracking.domain.model.unitofwork.Snapshot;
import com.nona.changeTracking.internal.util.ReflectionUtils;
import com.nona.changeTracking.spi.SnapshotCreationException;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.*;
import java.util.*;

/**
 * {@link SnapshotStrategy} 的一个实现，它通过反射递归地提取对象的状态，
 * 并将其存入一个嵌套的 {@code Map<String, Object>} 中。
 * <p>
 * 这个策略创建的是对象状态的<b>深度数据表示</b>，而非对象副本。它能正确处理
 * 复杂的对象图，包括嵌套对象、集合、Map以及循环引用，生成一个与原始对象图
 * 结构完全同构的Map结构。
 * <p>
 * 此类是无状态且线程安全的，可以作为单例使用。
 */
public final class ReflectionMapSnapshotStrategy implements SnapshotStrategy {

    /**
     * 提供一个可复用的单例实例。
     */
    public static final ReflectionMapSnapshotStrategy INSTANCE = new ReflectionMapSnapshotStrategy();

    @Override
    public Snapshot<?> createSnapshot(final Object object) {
        Objects.requireNonNull(object, "Cannot create snapshot for a null object.");
        try {
            final var visited = new IdentityHashMap<Object, Object>();
            final var mapRepresentation = toValueRecursive(object, visited);

            if (mapRepresentation instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> resultMap = (Map<String, Object>) map;
                return new MapSnapshot(resultMap);
            }
            throw new SnapshotCreationException("The root object did not resolve to a Map representation. It might be a simple type.", null);

        } catch (final Exception e) {
            throw new SnapshotCreationException("Failed to create snapshot for object: " + object, e);
        }
    }

    /**
     * 主递归方法，根据对象类型分发到专门的处理方法。
     *
     * @param obj     当前要处理的对象。
     * @param visited 用于跟踪已访问对象的Map，防止无限递归。
     * @return 对象的Map或值表示。
     */
    private Object toValueRecursive(final Object obj, final Map<Object, Object> visited) throws IllegalAccessException {
        if (obj == null) {
            return null;
        }

        if (visited.containsKey(obj)) {
            return visited.get(obj);
        }

        // 使用 Java 17 标准支持的 "instanceof 模式匹配" 重构
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof Enum<?> || obj instanceof UUID ||
            obj instanceof LocalDate || obj instanceof LocalTime || obj instanceof LocalDateTime || obj instanceof ZonedDateTime ||
            obj instanceof OffsetDateTime || obj instanceof Instant || obj instanceof Duration || obj instanceof Period) {
            // 所有不可变的简单值类型，直接返回
            return obj;
        } else if (obj instanceof Date d) {
            // Date 是可变的，必须创建其副本
            return d.clone();
        } else if (obj instanceof Collection<?> collection) {
            return processCollection(collection, visited);
        } else if (obj instanceof Map<?, ?> map) {
            return processMap(map, visited);
        } else {
            // 默认处理：假定为复杂的自定义对象
            if (obj.getClass().isPrimitive()) {
                return obj;
            }
            return processComplexObject(obj, visited);
        }
    }

    /**
     * 专门处理集合类型。
     */
    private Collection<Object> processCollection(final Collection<?> collection, final Map<Object, Object> visited) throws IllegalAccessException {
        final var newList = new ArrayList<>(collection.size());
        visited.put(collection, newList);

        for (final var element : collection) {
            newList.add(toValueRecursive(element, visited));
        }
        return newList;
    }

    /**
     * 专门处理Map类型。
     */
    private Map<Object, Object> processMap(final Map<?, ?> map, final Map<Object, Object> visited) throws IllegalAccessException {
        final var newMap = new LinkedHashMap<Object, Object>(map.size());
        visited.put(map, newMap);

        for (final var entry : map.entrySet()) {
            final Object key = entry.getKey();
            final Object value = entry.getValue();
            newMap.put(key, toValueRecursive(value, visited));
        }
        return newMap;
    }

    /**
     * 专门处理复杂的自定义对象（POJO）。
     */
    private Map<String, Object> processComplexObject(final Object obj, final Map<Object, Object> visited) throws IllegalAccessException {
        final var newMap = new LinkedHashMap<String, Object>();
        visited.put(obj, newMap);
        // 使用工具类，消除代码重复
        final List<Field> fields = ReflectionUtils.getAllFields(obj.getClass());
        for (final Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            final String fieldName = field.getName();
            final Object fieldValue = field.get(obj);
            newMap.put(fieldName, toValueRecursive(fieldValue, visited));
        }
        return newMap;
    }
}
