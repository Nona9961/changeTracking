package com.nona.changeTracking.common;

import java.lang.reflect.Array;
import java.util.Objects;

/**
 * 数组拷贝工具。
 */
public final class ArrayCopyUtils {

    /**
     * 工具类：禁止实例化。
     */
    private ArrayCopyUtils() {
    }

    /**
     * 复制数组：按组件类型新建同类型数组，数组层级逐层复制，元素按引用共享（一维为浅拷贝）。
     *
     * @param array 源数组，不能为 null 且必须是数组。
     * @return 内容相同的新数组，元素引用与源数组相同。
     * @throws NullPointerException     如果 array 为 null。
     * @throws IllegalArgumentException 如果 array 不是数组。
     */
    public static Object copyArray(final Object array) {
        Objects.requireNonNull(array, "Array must not be null.");
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException(
                    "ArrayCopyUtils.copyArray requires an array, got: " + array.getClass().getName());
        }

        final Class<?> componentType = array.getClass().getComponentType();
        final int length = Array.getLength(array);

        if (componentType.isArray()) {
            final Object copy = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, copyArray(Array.get(array, index)));
            }
            return copy;
        }

        final Object copy = Array.newInstance(componentType, length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }
}
