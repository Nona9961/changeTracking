package com.nona.changeTracking.internal.detector;

import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MapSnapshotComparator 单元测试")
class MapSnapshotComparatorTest {

    private MapSnapshotComparator comparator;

    // --- Test Data ---
    static class Parent {
        private final String parentField = "parent";
    }

    static class Child extends Parent {
        private String name = "initial";
        private int value = 10;
        private Object nullableField = null;
        private static final String STATIC_FIELD = "static";
        private transient String transientField = "transient";
    }

    @BeforeEach
    void setUp() {
        comparator = new MapSnapshotComparator();
    }

    private Map<String, Object> createFullSnapshotData() {
        final Map<String, Object> data = new HashMap<>();
        data.put("parentField", "parent");
        data.put("name", "initial");
        data.put("value", 10);
        data.put("nullableField", null);
        return data;
    }

    @Test
    @DisplayName("当对象未发生变化时，应返回空列表")
    void compare_whenNoChanges_shouldReturnEmptyList() {
        final Child original = new Child();
        final MapSnapshot snapshot = new MapSnapshot(createFullSnapshotData());
        final List<FieldChange> changes = comparator.compare(snapshot, original);
        assertTrue(changes.isEmpty());
    }

    @Test
    @DisplayName("当单个字段发生变化时，应返回包含一个变更的列表")
    void compare_whenSingleFieldChanges_shouldReturnSingleChange() {
        final Child modified = new Child();
        modified.name = "modified";
        final MapSnapshot snapshot = new MapSnapshot(createFullSnapshotData());
        final List<FieldChange> changes = comparator.compare(snapshot, modified);
        assertAll(
                () -> assertEquals(1, changes.size()),
                () -> assertEquals("name", changes.get(0).fieldName()),
                () -> assertEquals("initial", changes.get(0).oldValue()),
                () -> assertEquals("modified", changes.get(0).newValue())
        );
    }

    @Test
    @DisplayName("当多个字段发生变化时，应返回包含所有变更的列表")
    void compare_whenMultipleFieldsChange_shouldReturnAllChanges() {
        final Child modified = new Child();
        modified.name = "modified";
        modified.value = 20;
        final MapSnapshot snapshot = new MapSnapshot(createFullSnapshotData());
        final List<FieldChange> changes = comparator.compare(snapshot, modified);
        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(c -> c.fieldName().equals("name")));
        assertTrue(changes.stream().anyMatch(c -> c.fieldName().equals("value")));
    }

    @Test
    @DisplayName("当父类字段与快照不一致时，应能检测到")
    void compare_whenInheritedFieldDiffers_shouldDetectChange() {
        final Child original = new Child();
        final Map<String, Object> snapshotData = createFullSnapshotData();
        snapshotData.put("parentField", "DIFFERENT");
        final MapSnapshot snapshot = new MapSnapshot(snapshotData);
        final List<FieldChange> changes = comparator.compare(snapshot, original);
        assertEquals(1, changes.size());
        assertEquals("parentField", changes.get(0).fieldName());
    }

    @Test
    @DisplayName("当字段从 null 变为有值时，应能检测到")
    void compare_whenFieldBecomesNonNull_shouldDetectChange() {
        final Child modified = new Child();
        modified.nullableField = "not null";
        final MapSnapshot snapshot = new MapSnapshot(createFullSnapshotData());
        final List<FieldChange> changes = comparator.compare(snapshot, modified);
        assertEquals(1, changes.size());
        assertNull(changes.get(0).oldValue());
        assertEquals("not null", changes.get(0).newValue());
    }

    @Test
    @DisplayName("应忽略 static 和 transient 字段的比较")
    void compare_shouldIgnoreStaticAndTransientFields() {
        final Child original = new Child();
        final Map<String, Object> snapshotData = createFullSnapshotData();
        // 快照中不应该包含这些字段，但即使包含了，比较器也应该能正确处理
        snapshotData.put("STATIC_FIELD", "old_static");
        snapshotData.put("transientField", "old_transient");
        final MapSnapshot snapshot = new MapSnapshot(snapshotData);
        final List<FieldChange> changes = comparator.compare(snapshot, original);
        assertTrue(changes.isEmpty(), "比较结果应为空，因为只有非static/transient字段被比较");
    }
}
