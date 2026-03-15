package com.nona.changeTracking.domain.model.snapshot;

/**
 * 一个泛型化的值对象接口，作为领域对象状态快照的数据容器。
 * <p>
 * 这个接口的设计是“诚实的”，它承认不同的快照策略（SnapshotStrategy）
 * 可能会产生本质上不同类型的数据结构（例如，一个是数据的Map表示，另一个是对象的深拷贝副本）。
 * 通过这个容器，框架可以在类型安全的上下文中处理这些差异。
 *
 * @param <T> 快照内部数据的具体类型。
 */
public interface Snapshot<T> {

    /**
     * 获取快照所持有的内部数据。
     *
     * @return 快照数据，其类型由具体的实现类决定。
     */
    T getSnapshotData();
}
