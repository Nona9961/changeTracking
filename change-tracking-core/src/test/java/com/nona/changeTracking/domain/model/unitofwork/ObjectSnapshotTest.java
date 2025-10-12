package com.nona.changeTracking.domain.model.unitofwork;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ObjectSnapshot 值对象契约测试")
class ObjectSnapshotTest {

    private static class TestObject {
        private final int value;

        TestObject(int value) { this.value = value; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestObject that = (TestObject) o;
            return value == that.value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }
    }

    @Test
    @DisplayName("持有相等对象的 ObjectSnapshot 实例应该相等")
    void shouldBeEqualForEqualObjects() {
        final TestObject obj1 = new TestObject(100);
        final TestObject obj2 = new TestObject(100);

        final ObjectSnapshot snapshot1 = new ObjectSnapshot(obj1);
        final ObjectSnapshot snapshot2 = new ObjectSnapshot(obj2);

        assertEquals(snapshot1, snapshot2, "两个持有相等内部对象的ObjectSnapshot应该相等");
        assertEquals(snapshot1.hashCode(), snapshot2.hashCode(), "两个相等对象的hashCode应该相同");
    }

    @Test
    @DisplayName("持有不同对象的 ObjectSnapshot 实例应该不相等")
    void shouldNotBeEqualForDifferentObjects() {
        final TestObject obj1 = new TestObject(100);
        final TestObject obj2 = new TestObject(200);

        final ObjectSnapshot snapshot1 = new ObjectSnapshot(obj1);
        final ObjectSnapshot snapshot2 = new ObjectSnapshot(obj2);

        assertNotEquals(snapshot1, snapshot2, "两个持有不同内部对象的ObjectSnapshot不应该相等");
    }

    @Test
    @DisplayName("getSnapshotData() 应该返回构造时传入的对象")
    void shouldReturnCorrectData() {
        final Object originalObject = new Object();
        final ObjectSnapshot snapshot = new ObjectSnapshot(originalObject);

        assertSame(originalObject, snapshot.getSnapshotData(), "getSnapshotData应该返回原始对象");
    }
}
