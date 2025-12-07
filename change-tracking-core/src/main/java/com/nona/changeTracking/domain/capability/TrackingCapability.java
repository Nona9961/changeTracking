package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.spi.SnapshotStrategy;

/**
 * 定义了一个内聚的、完整的、类型安全的“变更追踪能力”单元。
 *
 * @param <S> 此能力单元所操作的 Snapshot 的具体类型。
 */
public interface TrackingCapability<S extends Snapshot<?>> {

    /**
     * 获取与此能力单元关联的快照创建策略。
     *
     * @return 用于创建对象快照的 {@link SnapshotStrategy} 实例。
     */
    SnapshotStrategy getSnapshotStrategy();

    /**
     * 获取与此能力单元关联的、类型安全的快照比较策略。
     *
     * @return 一个保证能处理类型 S 的 {@link ComparisonStrategy} 实例。
     */
    ComparisonStrategy<S> getComparisonStrategy();
}
