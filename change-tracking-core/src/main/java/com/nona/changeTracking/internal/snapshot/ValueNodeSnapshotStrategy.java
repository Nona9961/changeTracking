package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.snapshot.*;
import com.nona.changeTracking.internal.util.ReflectionUtils;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class ValueNodeSnapshotStrategy implements SnapshotStrategy {

    private static final Set<String> KNOWN_VALUE_PACKAGES = Set.of(
            "java.time",
            "java.math",
            "java.net"
    );
    // **【核心修正点】**: 使用精确的类集合，而不是宽泛的包名
    private static final Set<Class<?>> KNOWN_VALUE_CLASSES = Set.of(
            UUID.class
    );

    @Override
    public ValueNodeSnapshot createSnapshot(final Object entity) {
        if (entity == null) {
            return new ValueNodeSnapshot(new NullNode());
        }
        final ValueNode rootNode = toValueRecursive(entity, new IdentityHashMap<>());
        return new ValueNodeSnapshot(rootNode);
    }

    private ValueNode toValueRecursive(final Object obj, final Map<Object, ValueNode> visited) {
        if (obj == null) {
            return new NullNode();
        }
        if (visited.containsKey(obj)) {
            return visited.get(obj);
        }

        final Class<?> type = obj.getClass();

        if (ReflectionUtils.isPrimitiveOrWrapper(type) ||
            type.equals(String.class) ||
            type.isEnum() ||
            KNOWN_VALUE_PACKAGES.contains(type.getPackageName()) ||
            KNOWN_VALUE_CLASSES.contains(type)) {
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

    private ObjectNode processComplexObject(final Object obj, final Map<Object, ValueNode> visited) {
        final int identityHashCode = System.identityHashCode(obj);
        
        // **【核心修正点】**: 正确的循环引用处理逻辑
        // 1. 创建一个可变的、空的 fields map。
        final Map<String, ValueNode> fieldsMap = new HashMap<>();
        // 2. 创建一个包含这个 *可变 map 引用* 的 ObjectNode。
        final ObjectNode objectNode = new ObjectNode(fieldsMap, identityHashCode);
        // 3. 将这个 *唯一的* ObjectNode 实例放入缓存。
        visited.put(obj, objectNode);

        // 4. 递归处理所有字段，并将结果 *填充* 到我们之前创建的那个可变 map 中。
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
        
        // 5. 将递归得到的所有字段填充到原始的 map 中。
        fieldsMap.putAll(populatedFields);

        // 6. 返回最初创建的那个 ObjectNode 实例。
        return objectNode;
    }
}
