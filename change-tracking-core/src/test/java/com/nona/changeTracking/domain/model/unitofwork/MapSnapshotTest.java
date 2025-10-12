package com.nona.changeTracking.domain.model.unitofwork;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MapSnapshot 值对象契约测试")
class MapSnapshotTest {

    @Test
    @DisplayName("内容相同的 MapSnapshot 实例应该相等")
    void shouldBeEqualForSameContent() {
        final Map<String, Object> map1 = Map.of("id", 1L, "name", "test");
        final Map<String, Object> map2 = Map.of("id", 1L, "name", "test");

        final MapSnapshot snapshot1 = new MapSnapshot(map1);
        final MapSnapshot snapshot2 = new MapSnapshot(map2);

        assertEquals(snapshot1, snapshot2, "两个持有相同Map的MapSnapshot应该相等");
        assertEquals(snapshot1.hashCode(), snapshot2.hashCode(), "两个相等对象的hashCode应该相同");
    }

    @Test
    @DisplayName("内容不同的 MapSnapshot 实例应该不相等")
    void shouldNotBeEqualForDifferentContent() {
        final Map<String, Object> map1 = Map.of("id", 1L);
        final Map<String, Object> map2 = Map.of("id", 2L);

        final MapSnapshot snapshot1 = new MapSnapshot(map1);
        final MapSnapshot snapshot2 = new MapSnapshot(map2);

        assertNotEquals(snapshot1, snapshot2, "两个持有不同Map的MapSnapshot不应该相等");
    }

    @Test
    @DisplayName("getSnapshotData() 应该返回构造时传入的 Map")
    void shouldReturnCorrectData() {
        final Map<String, Object> originalMap = Collections.singletonMap("key", "value");
        final MapSnapshot snapshot = new MapSnapshot(originalMap);

        assertSame(originalMap, snapshot.getSnapshotData(), "getSnapshotData应该返回原始Map对象");
    }
}
