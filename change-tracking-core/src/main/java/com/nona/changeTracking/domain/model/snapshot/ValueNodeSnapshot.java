package com.nona.changeTracking.domain.model.snapshot;

/**
 * 基于 {@link ValueNode} 树结构的快照实现。
 * <p>
 * 这是框架默认的快照类型，将领域对象的状态表示为 ValueNode 树。
 * 由 {@link com.nona.changeTracking.internal.snapshot.ValueNodeSnapshotStrategy} 创建。
 *
 * @param snapshotData 快照的根节点，通常是一个 {@link ObjectNode}。
 */
public record ValueNodeSnapshot(ValueNode snapshotData) implements Snapshot<ValueNode> {

    /**
     * {@inheritDoc}
     */
    @Override
    public ValueNode getSnapshotData() {
        return snapshotData;
    }
}
