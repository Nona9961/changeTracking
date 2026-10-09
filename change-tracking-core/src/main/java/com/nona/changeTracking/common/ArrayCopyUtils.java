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
     * 一维：按组件类型创建同类型新数组并浅拷贝——元素已判定为值类型（不可变），浅拷贝安全，
     * 且保持运行时数组类型（消费方按原类型强转可用），与源数组不共享元素引用；
     * 多维：逐层递归深拷贝（内层行也是数组），每层同样按组件类型创建。
     *
     * @param array 源数组。
     * @return 内容相同、互不共享引用的新数组。
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
