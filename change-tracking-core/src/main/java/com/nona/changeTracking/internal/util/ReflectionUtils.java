package com.nona.changeTracking.internal.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class ReflectionUtils {

    /**
     * 包含所有包装类类型和原始类型的集合。
     */
    private static final Set<Class<?>> WRAPPER_TYPES = Set.of(
            Boolean.class, Character.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Void.class
    );

    /**
     * 私有构造函数，防止实例化。
     */
    private ReflectionUtils() {}

    /**
     * 递归地获取一个类及其所有父类的所有声明字段。
     * <p>
     * 遍历会持续到 {@code Object.class} 为止。
     *
     * @param clazz 目标类。
     * @return 包含所有字段的列表，包括私有和父类的字段。
     */
    public static List<Field> getAllFields(final Class<?> clazz) {
        final List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            currentClass = currentClass.getSuperclass();
        }
        return fields;
    }

    /**
     * 检查给定的类型是否是原始类型或其对应的包装类。
     *
     * @param clazz 要检查的类。
     * @return 如果是原始类型或包装类，则为 true。
     */
    public static boolean isPrimitiveOrWrapper(final Class<?> clazz) {
        return clazz.isPrimitive() || WRAPPER_TYPES.contains(clazz);
    }
}
