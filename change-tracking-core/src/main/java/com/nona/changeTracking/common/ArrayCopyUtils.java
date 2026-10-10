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
     * 复制数组：按组件类型新建同类型数组，数组层级逐层递归复制，元素按引用共享。
     * <p>
     * 语义边界：本方法复制的是<b>数组结构</b>，不复制元素对象。
     * <ul>
     *   <li><b>一维</b>：按组件类型新建同类型数组后浅拷贝——副本与源数组是不同的数组对象，
     *       元素引用相同。元素为不可变值（基本类型装箱、String、枚举、时间类等）时，引用共享
     *       不构成可变内容共享；元素为可变对象（POJO）时，副本与源数组持有同一元素实例，修改
     *       元素内部状态在两侧均可见——此时本方法<b>不</b>提供防御拷贝。</li>
     *   <li><b>多维</b>：组件类型仍是数组的内层行逐层递归复制，每层按组件类型新建；最内层
     *       元素仍按引用共享（同上）。</li>
     * </ul>
     * 需要连同元素对象一起复制的场景由调用方自行处理（例如快照树的结构重建由
     * {@code ValueNodeDeepCopier} 承担，数组元素在其中已归约为不可变值或子树）。
     *
     * @param array 源数组，不能为 null，且运行时类型必须是数组。
     * @return 内容相同的新数组：数组层级逐层复制，元素按引用共享。
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
