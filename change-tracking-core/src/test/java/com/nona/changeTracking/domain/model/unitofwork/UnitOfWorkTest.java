package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UnitOfWork 聚合测试")
@ExtendWith(MockitoExtension.class)
class UnitOfWorkTest {

    @Mock(lenient = true)
    private TrackingCapability<?> capability;
    @Mock(lenient = true)
    private SnapshotStrategy snapshotStrategy;
    @Mock(lenient = true)
    private ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy;

    private UnitOfWork uow;

    // --- Test Data ---
    static class User { String name; }
    private final User user1 = new User();
    private final User user2 = new User();
    private final ValueNodeSnapshot oldSnapshot = new ValueNodeSnapshot(null);
    private final ValueNodeSnapshot newSnapshot = new ValueNodeSnapshot(null);
    private final ChangeNode changeTree = new ContainerChangeNode("user", List.of(new FieldChangeNode("user.name", "a", "b")));
    private final ChangeNode noChangeTree = new ContainerChangeNode("", Collections.emptyList());


    @BeforeEach
    void setUp() {
        when(capability.getSnapshotStrategy()).thenReturn(snapshotStrategy);
        doReturn(comparisonStrategy).when(capability).getComparisonStrategy();
        uow = new UnitOfWork(capability);
    }

    @Nested
    @DisplayName("基本变更检测")
    class BasicChangeDetection {

        @Test
        @DisplayName("对于已变更的 clean 对象，应调用比较策略并生成 ChangeSet")
        void calculateChanges_forDirtyCleanObject_shouldCallComparisonAndCreateChangeSet() {
            // --- Arrange ---
            doReturn(oldSnapshot, newSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);

            when(comparisonStrategy.compare(oldSnapshot, newSnapshot)).thenReturn(changeTree);

            // --- Act ---
            final ChangeSet changeSet = uow.calculateChanges();

            // --- Assert ---
            assertFalse(changeSet.isEmpty());
            assertEquals(1, changeSet.changes().size());
            assertEquals(user1, changeSet.changes().get(0).target());
            assertEquals(changeTree, changeSet.changes().get(0).changeTree());

            verify(snapshotStrategy, times(2)).createSnapshot(user1);
            verify(comparisonStrategy, times(1)).compare(oldSnapshot, newSnapshot);
        }

        @Test
        @DisplayName("对于未变更的 clean 对象，不应生成变更")
        void calculateChanges_forUnchangedCleanObject_shouldNotCreateChange() {
            // --- Arrange ---
            doReturn(oldSnapshot, oldSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);

            when(comparisonStrategy.compare(oldSnapshot, oldSnapshot)).thenReturn(noChangeTree);

            // --- Act ---
            final ChangeSet changeSet = uow.calculateChanges();

            // --- Assert ---
            assertTrue(changeSet.isEmpty());
            verify(comparisonStrategy, times(1)).compare(oldSnapshot, oldSnapshot);
        }

        @Test
        @DisplayName("对于 new 对象，不应调用比较策略，且不生成变更")
        void calculateChanges_forNewObject_shouldNotCallComparisonAndNotCreateChange() {
            uow.registerNew(user1);
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
            verifyNoInteractions(snapshotStrategy);
            verifyNoInteractions(comparisonStrategy);
        }

        @Test
        @DisplayName("对于 removed 对象，不应调用比较策略，且不生成变更")
        void calculateChanges_forRemovedObject_shouldNotCallComparisonAndNotCreateChange() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);
            uow.registerClean(user1);
            uow.registerRemoved(user1);
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
            verify(comparisonStrategy, never()).compare(any(), any());
        }
    }

    @Nested
    @DisplayName("重复注册行为")
    class DuplicateRegistration {

        @Test
        @DisplayName("重复注册 clean 对象应被忽略")
        void registerClean_duplicate_shouldBeIgnored() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerClean(user1); // 重复注册

            // 只应调用一次快照创建
            verify(snapshotStrategy, times(1)).createSnapshot(user1);
        }

        @Test
        @DisplayName("已注册为 clean 的对象再注册为 new 应被忽略")
        void registerNew_afterClean_shouldBeIgnored() {
            doReturn(oldSnapshot, newSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerNew(user1); // 尝试再注册为 new

            when(comparisonStrategy.compare(oldSnapshot, newSnapshot)).thenReturn(changeTree);

            // 仍然应该追踪变更
            final ChangeSet changeSet = uow.calculateChanges();
            assertFalse(changeSet.isEmpty());
        }

        @Test
        @DisplayName("已注册为 new 的对象再注册为 clean 应被忽略")
        void registerClean_afterNew_shouldBeIgnored() {
            uow.registerNew(user1);
            uow.registerClean(user1); // 尝试再注册为 clean

            // 不应调用快照策略
            verifyNoInteractions(snapshotStrategy);

            // 仍然应该不产生变更
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
        }

        @Test
        @DisplayName("重复注册 removed 对象应被忽略")
        void registerRemoved_duplicate_shouldBeIgnored() {
            doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);

            uow.registerClean(user1);
            uow.registerRemoved(user1);
            uow.registerRemoved(user1); // 重复移除

            // 不应抛出异常，变更集应为空
            final ChangeSet changeSet = uow.calculateChanges();
            assertTrue(changeSet.isEmpty());
        }
    }

    @Nested
    @DisplayName("多对象追踪")
    class MultipleObjectTracking {

        @Test
        @DisplayName("应能同时追踪多个对象的变更")
        void shouldTrackMultipleObjects() {
            // 使用不同的 snapshotData 来区分每个快照，避免 record 的 equals() 导致 Mockito 参数匹配混乱
            final ValueNodeSnapshot oldSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("old1"));
            final ValueNodeSnapshot newSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("new1"));
            final ValueNodeSnapshot oldSnapshot2 = new ValueNodeSnapshot(new PrimitiveNode("old2"));
            final ValueNodeSnapshot newSnapshot2 = new ValueNodeSnapshot(new PrimitiveNode("new2"));

            doReturn(oldSnapshot1, newSnapshot1).when(snapshotStrategy).createSnapshot(user1);
            doReturn(oldSnapshot2, newSnapshot2).when(snapshotStrategy).createSnapshot(user2);

            uow.registerClean(user1);
            uow.registerClean(user2);

            final ChangeNode changeTree1 = new ContainerChangeNode("user1", List.of(new FieldChangeNode("name", "a", "b")));
            final ChangeNode changeTree2 = new ContainerChangeNode("user2", List.of(new FieldChangeNode("name", "c", "d")));

            when(comparisonStrategy.compare(oldSnapshot1, newSnapshot1)).thenReturn(changeTree1);
            when(comparisonStrategy.compare(oldSnapshot2, newSnapshot2)).thenReturn(changeTree2);

            final ChangeSet changeSet = uow.calculateChanges();

            assertEquals(2, changeSet.changes().size());
        }

        @Test
        @DisplayName("一个对象变更一个对象未变更时，只应生成一个变更")
        void oneChangedOneUnchanged_shouldProduceOneChange() {
            // 使用不同的 snapshotData 来区分每个快照
            final ValueNodeSnapshot snapshot1 = new ValueNodeSnapshot(new PrimitiveNode("s1"));
            final ValueNodeSnapshot newSnapshot1 = new ValueNodeSnapshot(new PrimitiveNode("ns1"));
            final ValueNodeSnapshot snapshot2 = new ValueNodeSnapshot(new PrimitiveNode("s2"));

            doReturn(snapshot1, newSnapshot1).when(snapshotStrategy).createSnapshot(user1);
            doReturn(snapshot2, snapshot2).when(snapshotStrategy).createSnapshot(user2);

            uow.registerClean(user1);
            uow.registerClean(user2);

            when(comparisonStrategy.compare(snapshot1, newSnapshot1)).thenReturn(changeTree);
            when(comparisonStrategy.compare(snapshot2, snapshot2)).thenReturn(noChangeTree);

            final ChangeSet changeSet = uow.calculateChanges();

            assertEquals(1, changeSet.changes().size());
            assertEquals(user1, changeSet.changes().get(0).target());
        }
    }

    @Nested
    @DisplayName("参数验证")
    class ParameterValidation {

        @Test
        @DisplayName("构造函数传入 null 应抛出 NullPointerException")
        void constructor_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> new UnitOfWork(null));
        }

        @Test
        @DisplayName("registerClean 传入 null 应抛出 NullPointerException")
        void registerClean_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerClean(null));
        }

        @Test
        @DisplayName("registerNew 传入 null 应抛出 NullPointerException")
        void registerNew_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerNew(null));
        }

        @Test
        @DisplayName("registerRemoved 传入 null 应抛出 NullPointerException")
        void registerRemoved_withNull_shouldThrowNPE() {
            assertThrows(NullPointerException.class, () -> uow.registerRemoved(null));
        }
    }
}
