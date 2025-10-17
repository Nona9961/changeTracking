package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;

/**
 * 定义了比较两个快照以生成细粒度变更树的策略接口。
 * <p>
 * 这是一个核心扩展点，允许框架支持不同格式快照的比较逻辑（如基于ValueNode树、JSON文档、Kryo二进制等）。
 *
 * @param <S> 此策略能够处理的 {@link Snapshot} 的具体类型。
 */
public interface ComparisonStrategy<S extends Snapshot<?>> {

    /**
     * 返回此比较策略能够处理的 {@link Snapshot} 的具体 Class 对象。
     * 这用于在运行时进行类型安全的策略分发。
     *
     * @return 支持的 Snapshot 类型的 Class。
     */
    Class<S> getSupportedSnapshotType();

    /**
     * 比较一个旧快照和一个新快照，生成描述两者之间所有差异的变更树。
     *
     * @param oldSnapshot 代表对象先前状态的快照。
     * @param newSnapshot 代表对象当前状态的快照。
     * @return 一个 {@link ChangeNode} 作为变更树的根节点。如果两个快照之间没有差异，
     *         可以返回一个特定的“无变更”节点或一个没有子节点的根节点。
     */
    ChangeNode compare(S oldSnapshot, S newSnapshot);
}
