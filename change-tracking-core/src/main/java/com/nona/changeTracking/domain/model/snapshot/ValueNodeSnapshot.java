package com.nona.changeTracking.domain.model.snapshot;

public record ValueNodeSnapshot(ValueNode snapshotData) implements Snapshot<ValueNode> {
    @Override
    public ValueNode getSnapshotData() {
        return snapshotData;
    }
}
