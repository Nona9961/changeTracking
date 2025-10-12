package com.nona.changeTracking.internal.detector;

import com.nona.changeTracking.domain.detector.SnapshotComparator;
import com.nona.changeTracking.internal.detector.MapSnapshotComparator;
import com.nona.changeTracking.spi.CreationContext;
import com.nona.changeTracking.spi.SnapshotComparatorProvider;

public class MapSnapshotComparatorProvider implements SnapshotComparatorProvider {

    /**
     * ServiceLoader 要求一个 public 的无参构造函数。
     */
    public MapSnapshotComparatorProvider() {}

    @Override
    public SnapshotComparator<?> create(final CreationContext context) {
        // 在这里实例化并返回。
        // 如果 MapSnapshotComparator 未来需要依赖，可以从 context 中获取。
        return new MapSnapshotComparator();
    }
}
