package com.nona.changeTracking.internal.detector;

import com.nona.changeTracking.domain.detector.SnapshotComparator;
import com.nona.changeTracking.domain.model.unitofwork.MapSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MapSnapshotComparatorProvider 单元测试")
class MapSnapshotComparatorProviderTest {

    private MapSnapshotComparatorProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MapSnapshotComparatorProvider();
    }

    @Test
    @DisplayName("create 方法应返回一个 MapSnapshotComparator 实例")
    void create_shouldReturnInstanceOfMapSnapshotComparator() {
        final SnapshotComparator<?> comparator = provider.create(null); // Context can be null for this test

        assertNotNull(comparator);
        assertInstanceOf(MapSnapshotComparator.class, comparator);
    }

    @Test
    @DisplayName("创建的 Comparator 应支持 MapSnapshot 类型")
    void createdComparator_shouldSupportMapSnapshotType() {
        final SnapshotComparator<?> comparator = provider.create(null);

        assertEquals(MapSnapshot.class, comparator.getSupportedSnapshotType());
    }
}
