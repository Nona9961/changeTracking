package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.snapshot.*;
import com.nona.changeTracking.internal.util.ReflectionUtils;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于反射的快照策略实现，将对象转换为 {@link ValueNode} 树。
 * <p>
 * 类型判断顺序：
 * <ol>
 *   <li>null → {@link NullNode}</li>
 *   <li>原始类型/包装类/String/枚举/已知值类型 → {@link PrimitiveNode}</li>
 *   <li>Collection/Map → {@link CollectionNode}</li>
 *   <li>其他复杂对象 → {@link ObjectNode}</li>
 * </ol>
 * <p>
 * 支持循环引用检测：使用 {@link IdentityHashMap} 缓存已访问对象，
 * 遇到循环引用时返回同一 ObjectNode 实例。
 */
public class ValueNodeSnapshotStrategy implements SnapshotStrategy {

    /**
     * 已知的值类型包名，这些包下的类会被视为原始值。
     */
    private static final Set<String> KNOWN_VALUE_PACKAGES = Set.of(
            "java.time",
            "java.math",
            "java.net"
    );

    /**
     * 已知的值类型类，这些类会被视为原始值。
     */
    private static final Set<Class<?>> KNOWN_VALUE_CLASSES = Set.of(
            UUID.class
    );

    /**
     * {@inheritDoc}
     */
    @Override
    public ValueNodeSnapshot createSnapshot(final Object entity) {
        if (entity == null) {
            return new ValueNodeSnapshot(new NullNode());
        }
        final ValueNode rootNode = toValueRecursive(entity, new IdentityHashMap<>());
        return new ValueNodeSnapshot(rootNode);
    }

    /**
     * 递归地将对象转换为 ValueNode。
     *
     * @param obj     要转换的对象。
     * @param visited 已访问对象的缓存，用于检测循环引用。
     * @return 对象的 ValueNode 表示。
     */
    private ValueNode toValueRecursive(final Object obj, final Map<Object, ValueNode> visited) {
        if (obj == null) {
            return new NullNode();
        }
        if (visited.containsKey(obj)) {
            return visited.get(obj);
        }

        final Class<?> type = obj.getClass();

        if (ReflectionUtils.isPrimitiveOrWrapper(type)
                || type.equals(String.class)
                || type.isEnum()
                || KNOWN_VALUE_PACKAGES.contains(type.getPackageName())
                || KNOWN_VALUE_CLASSES.contains(type)) {
            return new PrimitiveNode(obj);
        }

        if (obj instanceof Collection<?> collection) {
            return new CollectionNode(
                    collection.stream()
                            .map(item -> toValueRecursive(item, visited))
                            .collect(Collectors.toList())
            );
        }

        if (obj instanceof Map<?, ?> map) {
            return new CollectionNode(
                    map.entrySet().stream()
                            .map(entry -> toValueRecursive(entry, visited))
                            .collect(Collectors.toList())
            );
        }

        return processComplexObject(obj, visited);
    }

    /**
     * 处理复杂对象，将其转换为 ObjectNode。
     * <p>
     * 循环引用处理逻辑：
     * <ol>
     *   <li>创建空的 fields map</li>
     *   <li>创建 ObjectNode 并放入缓存</li>
     *   <li>递归处理所有字段</li>
     *   <li>将字段填充到 map 中</li>
     * </ol>
     * 这样即使遇到循环引用，也能返回同一个 ObjectNode 实例。
     *
     * @param obj     要处理的复杂对象。
     * @param visited 已访问对象的缓存。
     * @return 对象的 ObjectNode 表示。
     */
    private ObjectNode processComplexObject(final Object obj, final Map<Object, ValueNode> visited) {
        final int identityHashCode = System.identityHashCode(obj);

        final Map<String, ValueNode> fieldsMap = new HashMap<>();
        final ObjectNode objectNode = new ObjectNode(fieldsMap, identityHashCode);
        visited.put(obj, objectNode);

        final Map<String, ValueNode> populatedFields = ReflectionUtils.getAllFields(obj.getClass()).stream()
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.toMap(
                        Field::getName,
                        field -> {
                            try {
                                return toValueRecursive(field.get(obj), visited);
                            } catch (IllegalAccessException e) {
                                throw new IllegalStateException("Failed to access field: " + field.getName(), e);
                            }
                        }
                ));

        fieldsMap.putAll(populatedFields);

        return objectNode;
    }
}
