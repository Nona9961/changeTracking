package com.nona.changeTracking.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArrayCopyUtils#copyArray(Object)} 的入参契约与复制语义测试。
 */
@DisplayName("ArrayCopyUtils 数组拷贝单元测试")
class ArrayCopyUtilsUnitTest {

    /**
     * 可变元素探针：用于验证元素按引用共享，而非深拷贝。
     */
    private static final class MutableElement {

        private int value;

        private MutableElement(final int value) {
            this.value = value;
        }
    }

    @Nested
    @DisplayName("入参契约")
    class ArgumentContract {

        @Test
        @DisplayName("null 入参抛 NullPointerException")
        void copyArray_withNull_shouldThrowNpe() {
            assertThatThrownBy(() -> ArrayCopyUtils.copyArray(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Array must not be null");
        }

        @Test
        @DisplayName("非数组入参抛 IllegalArgumentException")
        void copyArray_withNonArray_shouldThrowIae() {
            assertThatThrownBy(() -> ArrayCopyUtils.copyArray(new MutableElement(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires an array");
        }
    }

    @Nested
    @DisplayName("复制语义")
    class CopySemantics {

        @Test
        @DisplayName("一维基本类型数组：副本独立于源数组")
        void copyArray_primitiveArray_shouldBeIndependent() {
            final int[] source = {1, 2, 3};

            final int[] copy = (int[]) ArrayCopyUtils.copyArray(source);
            copy[0] = 9;

            assertThat(source).containsExactly(1, 2, 3);
            assertThat(copy).containsExactly(9, 2, 3);
        }

        @Test
        @DisplayName("一维对象数组：外层为新数组，元素按引用共享")
        void copyArray_objectArray_shouldShareElements() {
            final MutableElement[] source = {new MutableElement(1), new MutableElement(2)};

            final MutableElement[] copy = (MutableElement[]) ArrayCopyUtils.copyArray(source);
            assertThat(copy).isNotSameAs(source);
            assertThat(copy[0]).isSameAs(source[0]);

            copy[0].value = 99;
            assertThat(source[0].value).isEqualTo(99);
        }

        @Test
        @DisplayName("二维数组：内层行为新数组，最内层元素仍共享")
        void copyArray_twoDimensionalArray_shouldCopyRowsAndShareElements() {
            final MutableElement[][] source = {{new MutableElement(1)}, {new MutableElement(2)}};

            final MutableElement[][] copy = (MutableElement[][]) ArrayCopyUtils.copyArray(source);
            assertThat(copy).isNotSameAs(source);
            assertThat(copy[0]).isNotSameAs(source[0]);
            assertThat(copy[0][0]).isSameAs(source[0][0]);
        }

        @Test
        @DisplayName("运行时数组类型与组件类型保持")
        void copyArray_shouldKeepRuntimeArrayType() {
            final Object copy = ArrayCopyUtils.copyArray(new String[] {"a"});

            assertThat(copy.getClass()).isEqualTo(String[].class);
            assertThat((String[]) copy).containsExactly("a");
        }
    }
}
