package com.nona.changeTracking.domain.changeset;

import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChangeSet 相关值对象契约测试")
class ChangeSetValueObjectTest {

    // FieldChangeTest 和 ObjectChangeTest 保持不变...
    @Nested
    @DisplayName("FieldChange Record 测试")
    class FieldChangeTest {
        @Test
        @DisplayName("内容相同的 FieldChange 实例应该相等")
        void shouldBeEqualForSameContent() {
            final FieldChange change1 = new FieldChange("name", "old", "new");
            final FieldChange change2 = new FieldChange("name", "old", "new");
            assertEquals(change1, change2);
            assertEquals(change1.hashCode(), change2.hashCode());
        }

        @Test
        @DisplayName("fieldName 为 null 时应抛出异常")
        void shouldThrowExceptionForNullFieldName() {
            assertThrows(NullPointerException.class, () -> new FieldChange(null, "old", "new"));
        }
    }

    @Nested
    @DisplayName("ObjectChange Record 测试")
    class ObjectChangeTest {
        @Test
        @DisplayName("内容相同的 ObjectChange 实例应该相等")
        void shouldBeEqualForSameContent() {
            final Object obj = new Object();
            final List<FieldChange> changes = List.of(new FieldChange("f", 1, 2));
            final ObjectChange change1 = new ObjectChange(obj, changes);
            final ObjectChange change2 = new ObjectChange(obj, changes);
            assertEquals(change1, change2);
            assertEquals(change1.hashCode(), change2.hashCode());
        }

        @Test
        @DisplayName("构造后修改外部 List 不应影响内部状态")
        void internalListShouldBeImmutable() {
            final Object obj = new Object();
            final List<FieldChange> originalChanges = new ArrayList<>();
            originalChanges.add(new FieldChange("f", 1, 2));

            final ObjectChange objectChange = new ObjectChange(obj, originalChanges);
            // 尝试修改原始 list
            originalChanges.add(new FieldChange("g", 3, 4));

            assertEquals(1, objectChange.fieldChanges().size(), "内部列表不应被外部修改所影响");
            assertThrows(UnsupportedOperationException.class, () -> objectChange.fieldChanges().add(null));
        }

    }
    @Test
    @DisplayName("toString 方法应返回简洁的摘要格式")
    void toStringShouldReturnConciseSummary() {
        final List<Object> newObjs = List.of("new1", "new2");
        final List<ObjectChange> dirtyObjs = List.of(new ObjectChange("dirty", Collections.emptyList()));
        final List<Object> removedObjs = Collections.emptyList();
        final ChangeSet changeSet = ChangeSet.of(newObjs, dirtyObjs, removedObjs);
        final String expected = "ChangeSet{newObjects=2, dirtyObjects=1, removedObjects=0}";
        assertEquals(expected, changeSet.toString());
    }

    @Nested
    @DisplayName("ChangeSet Class (Lombok) 测试")
    class ChangeSetTest {

        @Test
        @DisplayName("静态工厂方法 of() 应正确初始化所有字段")
        void factoryMethodShouldInitializeFields() {
            final List<Object> newObjs = List.of("new");
            final List<ObjectChange> dirtyObjs = List.of(new ObjectChange("dirty", Collections.emptyList()));
            final List<Object> removedObjs = List.of("removed");

            // 使用静态工厂方法创建实例
            final ChangeSet changeSet = ChangeSet.of(newObjs, dirtyObjs, removedObjs);

            assertEquals(newObjs, changeSet.getNewObjects());
            assertEquals(dirtyObjs, changeSet.getDirtyObjects());
            assertEquals(removedObjs, changeSet.getRemovedObjects());
        }

        @Test
        @DisplayName("通过 of() 创建后，修改外部 List 不应影响 ChangeSet 内部状态")
        void internalListsShouldBeImmutableWhenCreatedViaFactory() {
            final List<Object> originalNew = new ArrayList<>(List.of("new"));
            final List<ObjectChange> originalDirty = new ArrayList<>();
            final List<Object> originalRemoved = new ArrayList<>();

            final ChangeSet changeSet = ChangeSet.of(originalNew, originalDirty, originalRemoved);

            // 尝试修改原始 lists
            originalNew.add("another new");

            assertEquals(1, changeSet.getNewObjects().size(), "内部 newObjects 列表不应被外部修改所影响");
        }

        @Test
        @DisplayName("Getter 返回的 List 应该是不可变的")
        void gettersShouldReturnImmutableLists() {
            final ChangeSet changeSet = ChangeSet.empty();

            assertThrows(UnsupportedOperationException.class, () -> changeSet.getNewObjects().add("test"));
            assertThrows(UnsupportedOperationException.class, () -> changeSet.getDirtyObjects().add(null));
            assertThrows(UnsupportedOperationException.class, () -> changeSet.getRemovedObjects().add("test"));
        }

        @Test
        @DisplayName("isEmpty 和 empty 方法应能正确工作")
        void isEmptyAndEmptyFactoryShouldWorkCorrectly() {
            final ChangeSet emptySet = ChangeSet.empty();
            assertTrue(emptySet.isEmpty());
            assertEquals(0, emptySet.getNewObjects().size());

            final ChangeSet nonEmptySet = ChangeSet.of(List.of("new"), Collections.emptyList(), Collections.emptyList());
            assertFalse(nonEmptySet.isEmpty());
        }
    }

}
