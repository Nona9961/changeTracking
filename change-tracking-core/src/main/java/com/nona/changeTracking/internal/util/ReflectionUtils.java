package com.nona.changeTracking.internal.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一个内部工具类，提供与反射相关的静态辅助方法。
 * <p>
 * 这个类的目的是封装通用的反射操作，以避免在代码库中出现重复。
 * 此类不应被外部用户直接使用。
 */
public final class ReflectionUtils {

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
    public static List<Field> getAllFields(Class<?> clazz) {
        final List<Field> fields = new ArrayList<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            fields.addAll(Arrays.asList(currentClass.getDeclaredFields()));
            // 修正了语法错误：getSuperclass -> getSuperclass()
            currentClass = currentClass.getSuperclass();
        }
        return fields;
    }
}
