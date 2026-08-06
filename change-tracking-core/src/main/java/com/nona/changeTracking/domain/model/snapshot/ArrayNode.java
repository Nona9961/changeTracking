package com.nona.changeTracking.domain.model.snapshot;

import java.util.Arrays;
import java.util.Objects;

/**
 * 表示数组值的快照节点。
 * <p>
 * 数组 = <b>值语义（顺序敏感）</b>：{@code {1,2,3}} 与 {@code {3,2,1}} 是<b>不同</b>的值；
 * 需要顺序无关语义的场景应使用 {@code List}（集合语义，identifier 匹配）。
 * 业界共识：Hibernate {@code EqualsHelper} 对基本数组按类型 {@code Arrays.equals}、
 * {@code Object[]} 元素级比较；JaVers 将数组按 primitive 值处理。
 * <p>
 * 覆盖范围：
 * <ul>
 *   <li>8 种基本数组（boolean/byte/char/short/int/long/float/double）→ 对应
 *       {@link Arrays#equals} / {@link Arrays#hashCode}</li>
 *   <li>Object[]（含多维、元素为数组）→ {@link Arrays#deepEquals} / {@link Arrays#deepHashCode}</li>
 * </ul>
 * <p>
 * <b>不可变契约</b>：数组本身可变，本节点持有调用方传入的引用——快照构建方
 * （{@code ValueNodeSnapshotStrategy}）负责传入防御拷贝（一维浅拷贝、多维递归深拷贝）；
 * 直接构造本节点的调用方必须自行保证传入后不再修改数组，否则 equals 结果会漂移。
 *
 * @param array 数组对象，不能为 null 且必须 {@link Class#isArray()}。
 */
public record ArrayNode(Object array) implements ValueNode {

    /**
     * 紧凑构造器：校验参数合法性。
     *
     * @param array 数组对象。
     * @throws NullPointerException     如果 array 为 null。
     * @throws IllegalArgumentException 如果 array 不是数组。
     */
    public ArrayNode {
        Objects.requireNonNull(array, "Array must not be null.");
        if (!array.getClass().isArray()) {
            throw new IllegalArgumentException("ArrayNode requires an array, got: " + array.getClass().getName());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：组件类型相同且内容相等（基本数组按类型 {@code Arrays.equals}，
     * Object[] 含多维按 {@code Arrays.deepEquals}）。
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArrayNode that)) {
            return false;
        }
        return arrayEquals(this.array, that.array);
    }

    /**
     * 按内容比较两个数组。
     * <p>
     * 先比较组件类型（不同即不等——{@code byte[]} 与 {@code int[]} 不是同一值），
     * 再按类型分发到对应的 {@code Arrays.equals} / {@code deepEquals}。
     *
     * @param a 左侧数组。
     * @param b 右侧数组。
     * @return 内容相等返回 true。
     */
    private static boolean arrayEquals(final Object a, final Object b) {
        final Class<?> aComponent = a.getClass().getComponentType();
        final Class<?> bComponent = b.getClass().getComponentType();
        if (aComponent != bComponent) {
            return false;
        }
        if (aComponent.isPrimitive()) {
            if (aComponent == boolean.class) {
                return Arrays.equals((boolean[]) a, (boolean[]) b);
            }
            if (aComponent == byte.class) {
                return Arrays.equals((byte[]) a, (byte[]) b);
            }
            if (aComponent == char.class) {
                return Arrays.equals((char[]) a, (char[]) b);
            }
            if (aComponent == short.class) {
                return Arrays.equals((short[]) a, (short[]) b);
            }
            if (aComponent == int.class) {
                return Arrays.equals((int[]) a, (int[]) b);
            }
            if (aComponent == long.class) {
                return Arrays.equals((long[]) a, (long[]) b);
            }
            if (aComponent == float.class) {
                return Arrays.equals((float[]) a, (float[]) b);
            }
            return Arrays.equals((double[]) a, (double[]) b);
        }
        return Arrays.deepEquals((Object[]) a, (Object[]) b);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：与 {@link #equals(Object)} 对应——基本数组按类型 {@code Arrays.hashCode}，
     * Object[] 含多维按 {@code Arrays.deepHashCode}。相等对象 hashCode 一致。
     */
    @Override
    public int hashCode() {
        return arrayHashCode(this.array);
    }

    /**
     * 按内容计算数组 hashCode。
     *
     * @param array 目标数组。
     * @return 数组的内容 hashCode。
     */
    private static int arrayHashCode(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        if (componentType.isPrimitive()) {
            if (componentType == boolean.class) {
                return Arrays.hashCode((boolean[]) array);
            }
            if (componentType == byte.class) {
                return Arrays.hashCode((byte[]) array);
            }
            if (componentType == char.class) {
                return Arrays.hashCode((char[]) array);
            }
            if (componentType == short.class) {
                return Arrays.hashCode((short[]) array);
            }
            if (componentType == int.class) {
                return Arrays.hashCode((int[]) array);
            }
            if (componentType == long.class) {
                return Arrays.hashCode((long[]) array);
            }
            if (componentType == float.class) {
                return Arrays.hashCode((float[]) array);
            }
            return Arrays.hashCode((double[]) array);
        }
        return Arrays.deepHashCode((Object[]) array);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：展示数组内容（{@code Arrays.toString} / {@code Arrays.deepToString}），
     * 便于调试。
     */
    @Override
    public String toString() {
        return "ArrayNode[array=" + arrayToString(this.array) + "]";
    }

    /**
     * 生成数组内容字符串。
     *
     * @param array 目标数组。
     * @return 数组的内容字符串。
     */
    private static String arrayToString(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        if (componentType.isPrimitive()) {
            if (componentType == boolean.class) {
                return Arrays.toString((boolean[]) array);
            }
            if (componentType == byte.class) {
                return Arrays.toString((byte[]) array);
            }
            if (componentType == char.class) {
                return Arrays.toString((char[]) array);
            }
            if (componentType == short.class) {
                return Arrays.toString((short[]) array);
            }
            if (componentType == int.class) {
                return Arrays.toString((int[]) array);
            }
            if (componentType == long.class) {
                return Arrays.toString((long[]) array);
            }
            if (componentType == float.class) {
                return Arrays.toString((float[]) array);
            }
            return Arrays.toString((double[]) array);
        }
        return Arrays.deepToString((Object[]) array);
    }
}
