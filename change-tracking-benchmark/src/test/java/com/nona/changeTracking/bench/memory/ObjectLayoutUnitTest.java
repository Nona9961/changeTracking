package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ObjectLayout}: the shallow size of primitive and reference arrays, of plain
 * and inherited field carriers, and of representative JDK classes.
 * <p>
 * Every expected byte count is the value the estimator has to reproduce for the measured HotSpot
 * layout; the counts of the carriers and the arrays were cross checked against exact allocation sizes
 * (see the task report), so the assertions lock the layout rules, not the implementation.
 */
@DisplayName("ObjectLayout 对象浅尺寸估算单元测试")
class ObjectLayoutUnitTest {

    /** Carrier without an instance field: header only. */
    static final class Empty {
    }

    /** Carrier with one int field. */
    static final class OneInt {

        /** Payload. */
        int value;
    }

    /** Carrier with two int fields. */
    static final class TwoInt {

        /** First payload. */
        int first;

        /** Second payload. */
        int second;
    }

    /** Carrier with one long field. */
    static final class OneLong {

        /** Payload. */
        long value;
    }

    /** Carrier with one int and one long field. */
    static final class IntLong {

        /** Int payload. */
        int first;

        /** Long payload. */
        long second;
    }

    /** Carrier with one reference field. */
    static final class RefOnly {

        /** Reference payload. */
        Object value;
    }

    /** Carrier with one int and one reference field. */
    static final class IntRef {

        /** Int payload. */
        int first;

        /** Reference payload. */
        Object second;
    }

    /** Base carrier of the inherited field case. */
    static class Base {

        /** Inherited int payload. */
        int baseValue;
    }

    /** Subclass mixing an inherited int with its own int, long and reference fields. */
    static final class Sub extends Base {

        /** Own int payload. */
        int ownInt;

        /** Own long payload. */
        long ownLong;

        /** Own reference payload. */
        Object ownRef;
    }

    @Test
    @DisplayName("原始类型数组的浅尺寸应等于数组头加元素字节并对齐到 8 字节")
    void shallowSizeOfArray_shouldFollowPrimitiveElementLayout() {
        assertThat(ObjectLayout.shallowSizeOfArray(new int[0])).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfArray(new int[1])).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfArray(new int[4])).isEqualTo(32L);
        assertThat(ObjectLayout.shallowSizeOfArray(new long[3])).isEqualTo(40L);
        assertThat(ObjectLayout.shallowSizeOfArray(new byte[9])).isEqualTo(32L);
        assertThat(ObjectLayout.shallowSizeOfArray(new char[3])).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfArray(new short[5])).isEqualTo(32L);
        assertThat(ObjectLayout.shallowSizeOfArray(new double[1])).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfArray(new boolean[10])).isEqualTo(32L);
    }

    @Test
    @DisplayName("引用类型数组的每个元素槽位应为压缩指针的 4 字节")
    void shallowSizeOfArray_shouldUseCompressedReferenceSlots() {
        assertThat(ObjectLayout.shallowSizeOfArray(new Object[1])).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfArray(new Object[3])).isEqualTo(32L);
        assertThat(ObjectLayout.shallowSizeOfArray(new String[2])).isEqualTo(24L);
    }

    @Test
    @DisplayName("对象浅尺寸应等于对象头加实例字段并对齐到 8 字节")
    void shallowSizeOfClass_shouldFollowInstanceFieldLayout() {
        assertThat(ObjectLayout.shallowSizeOfClass(Empty.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(OneInt.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(TwoInt.class)).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfClass(OneLong.class)).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfClass(IntLong.class)).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfClass(RefOnly.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(IntRef.class)).isEqualTo(24L);
    }

    @Test
    @DisplayName("继承的实例字段应计入子类的浅尺寸")
    void shallowSizeOfClass_shouldIncludeInheritedFields() {
        assertThat(ObjectLayout.shallowSizeOfClass(Sub.class)).isEqualTo(32L);
    }

    @Test
    @DisplayName("常见 JDK 类的浅尺寸应反映其真实字段布局")
    void shallowSizeOfClass_shouldFollowJdkFieldLayout() {
        assertThat(ObjectLayout.shallowSizeOfClass(Object.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(Integer.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(Long.class)).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfClass(Boolean.class)).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSizeOfClass(String.class)).isEqualTo(24L);
        assertThat(ObjectLayout.shallowSizeOfClass(ArrayList.class)).isEqualTo(24L);
    }

    @Test
    @DisplayName("shallowSize 应按实例是否为数组分派到对应布局")
    void shallowSize_shouldDispatchByInstanceKind() {
        assertThat(ObjectLayout.shallowSize(new int[4])).isEqualTo(32L);
        assertThat(ObjectLayout.shallowSize(new Empty())).isEqualTo(16L);
        assertThat(ObjectLayout.shallowSize(new Sub())).isEqualTo(32L);
    }

    @Test
    @DisplayName("非法入参应被拒绝")
    void invalidInput_shouldBeRejected() {
        assertThatThrownBy(() -> ObjectLayout.shallowSize(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObjectLayout.shallowSizeOfClass(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObjectLayout.shallowSizeOfArray(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ObjectLayout.shallowSizeOfClass(int.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectLayout.shallowSizeOfClass(int[].class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObjectLayout.shallowSizeOfArray("not an array"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
