package com.nona.changeTracking.domain.model.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ArrayNode 单元测试（D14：数组=值语义，顺序敏感）")
class ArrayNodeTest {

    @Nested
    @DisplayName("构造校验")
    class ConstructorValidationTests {

        @Test
        @DisplayName("null 数组应抛 NullPointerException")
        void nullArray_shouldThrowNullPointerException() {
            assertThrows(NullPointerException.class, () -> new ArrayNode(null));
        }

        @Test
        @DisplayName("非数组对象应抛 IllegalArgumentException")
        void nonArray_shouldThrowIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> new ArrayNode("not-an-array"));
        }
    }

    @Nested
    @DisplayName("equals/hashCode 内容语义")
    class EqualityTests {

        @Test
        @DisplayName("byte[] 内容相等（不同实例）应相等且 hashCode 一致")
        void byteArray_contentEqual_shouldBeEqual() {
            final ArrayNode a = new ArrayNode(new byte[]{1, 2, 3});
            final ArrayNode b = new ArrayNode(new byte[]{1, 2, 3});

            assertEquals(a, b, "内容相同的数组应相等（内容语义，非引用语义）");
            assertEquals(a.hashCode(), b.hashCode(), "相等对象 hashCode 必须一致");
        }

        @Test
        @DisplayName("顺序不同应不相等（{1,2,3} ≠ {3,2,1}——数组=值语义，顺序敏感）")
        void byteArray_differentOrder_shouldNotBeEqual() {
            final ArrayNode a = new ArrayNode(new byte[]{1, 2, 3});
            final ArrayNode b = new ArrayNode(new byte[]{3, 2, 1});

            assertNotEquals(a, b, "数组顺序敏感：{1,2,3} 与 {3,2,1} 不是同一值");
        }

        @Test
        @DisplayName("内容不同应不相等")
        void byteArray_differentContent_shouldNotBeEqual() {
            final ArrayNode a = new ArrayNode(new byte[]{1, 2, 3});
            final ArrayNode b = new ArrayNode(new byte[]{1, 2, 4});

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("组件类型不同应不相等（byte[] vs int[]）")
        void differentComponentType_shouldNotBeEqual() {
            final ArrayNode a = new ArrayNode(new byte[]{1, 2});
            final ArrayNode b = new ArrayNode(new int[]{1, 2});

            assertNotEquals(a, b, "组件类型不同的数组不是同一值");
        }

        @Test
        @DisplayName("String[] 内容相等（不同实例）应相等，顺序不同不相等")
        void stringArray_contentEqual_shouldBeEqual() {
            final ArrayNode a = new ArrayNode(new String[]{"a", "b"});
            final ArrayNode b = new ArrayNode(new String[]{"a", "b"});

            assertEquals(a, b);
            assertNotEquals(a, new ArrayNode(new String[]{"b", "a"}));
        }

        @Test
        @DisplayName("多维数组内容相等（deepEquals）应相等且 hashCode 一致")
        void multiDimArray_contentEqual_shouldBeEqual() {
            final ArrayNode a = new ArrayNode(new int[][]{{1, 2}, {3, 4}});
            final ArrayNode b = new ArrayNode(new int[][]{{1, 2}, {3, 4}});

            assertEquals(a, b, "多维数组应按元素深度比较（deepEquals）");
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("多维数组内容不同应不相等")
        void multiDimArray_differentContent_shouldNotBeEqual() {
            final ArrayNode a = new ArrayNode(new int[][]{{1, 2}, {3, 4}});
            final ArrayNode b = new ArrayNode(new int[][]{{1, 2}, {5, 4}});

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Object[] 嵌套数组应 deepEquals（元素级比较）")
        void objectArray_withNestedArrays_shouldDeepEqual() {
            final ArrayNode a = new ArrayNode(new Object[]{new int[]{1, 2}, "x"});
            final ArrayNode b = new ArrayNode(new Object[]{new int[]{1, 2}, "x"});

            assertEquals(a, b, "Object[] 元素是数组时按元素深度比较");
        }

        @Test
        @DisplayName("所有 8 种基本数组类型应内容比较")
        void allPrimitiveArrayTypes_shouldCompareByContent() {
            assertEquals(new ArrayNode(new boolean[]{true, false}), new ArrayNode(new boolean[]{true, false}));
            assertEquals(new ArrayNode(new char[]{'a', 'b'}), new ArrayNode(new char[]{'a', 'b'}));
            assertEquals(new ArrayNode(new short[]{1, 2}), new ArrayNode(new short[]{1, 2}));
            assertEquals(new ArrayNode(new int[]{1, 2}), new ArrayNode(new int[]{1, 2}));
            assertEquals(new ArrayNode(new long[]{1L, 2L}), new ArrayNode(new long[]{1L, 2L}));
            assertEquals(new ArrayNode(new float[]{1.0f, 2.0f}), new ArrayNode(new float[]{1.0f, 2.0f}));
            assertEquals(new ArrayNode(new double[]{1.0d, 2.0d}), new ArrayNode(new double[]{1.0d, 2.0d}));
            assertEquals(new ArrayNode(new byte[]{1, 2}), new ArrayNode(new byte[]{1, 2}));
        }

        @Test
        @DisplayName("equals 应对称（正向与反向比较一致）")
        void equals_shouldBeSymmetric() {
            final ArrayNode a = new ArrayNode(new int[]{1, 2, 3});
            final ArrayNode b = new ArrayNode(new int[]{1, 2, 3});
            final ArrayNode c = new ArrayNode(new int[]{3, 2, 1});

            assertEquals(a.equals(b), b.equals(a), "相等方向对称");
            assertEquals(b.equals(c), c.equals(b), "不等方向对称");
        }
    }

    @Nested
    @DisplayName("toString 内容语义")
    class ToStringTests {

        @Test
        @DisplayName("toString 应展示数组内容（非 identity 哈希）")
        void toString_shouldShowArrayContent() {
            final ArrayNode node = new ArrayNode(new int[]{1, 2, 3});

            assertTrue(node.toString().contains("[1, 2, 3]"), "toString 应包含数组内容，实际: " + node);
        }
    }
}
