package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

    @Mock
    private TrackingCapability<?> capability;
    @Mock
    private SnapshotStrategy snapshotStrategy;
    @Mock
    private ComparisonStrategy<ValueNodeSnapshot> comparisonStrategy;

    private UnitOfWork uow;

    // --- Test Data ---
    static class User { String name; }
    private final User user1 = new User();
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

    @Test
    @DisplayName("对于已变更的 clean 对象，应调用比较策略并生成 ChangeSet")
    void calculateChanges_forDirtyCleanObject_shouldCallComparisonAndCreateChangeSet() {
        // --- Arrange ---
        // **【核心修正点】** 对所有返回泛型的方法，全面使用 doReturn(...).when(...)
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
        // **【核心修正点】** 全面使用 doReturn(...).when(...)
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
        // **【核心修正点】** 全面使用 doReturn(...).when(...)
        doReturn(oldSnapshot).when(snapshotStrategy).createSnapshot(user1);
        uow.registerClean(user1);
        uow.registerRemoved(user1);
        final ChangeSet changeSet = uow.calculateChanges();
        assertTrue(changeSet.isEmpty());
        verify(comparisonStrategy, never()).compare(any(), any());
    }
}
