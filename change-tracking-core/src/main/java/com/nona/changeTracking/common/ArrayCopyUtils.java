package com.nona.changeTracking.common;

import java.lang.reflect.Array;

/**
 * 数组防御拷贝工具。
 */
public final class ArrayCopyUtils {

    /**
     * 工具类：禁止实例化。
     */
    private ArrayCopyUtils() {
    }

    /**
     * 深拷贝数组（防御拷贝）。
     * <p>
     * 一维：按组件类型创建同类型新数组并浅拷贝——元素已判定为值类型（不可变），元素引用虽与源数组
     * 相同但不可变，故与源数组不共享可变内容；同时保持运行时数组类型（消费方按原类型强转可用）。
     * 多维：逐层递归深拷贝（内层行也是数组），每层同样按组件类型创建。
     *
     * @param array 源数组。
     * @return 内容相同的新数组；多维为逐层深拷贝，一维为同类型浅拷贝（元素不可变，引用共享安全）。
     */
    public static Object deepCopy(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        final int length = Array.getLength(array);

        if (componentType.isArray()) {
            final Object copy = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, deepCopy(Array.get(array, index)));
            }
            return copy;
        }

        final Object copy = Array.newInstance(componentType, length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }
}
