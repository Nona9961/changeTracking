package com.nona.changeTracking.domain.detector;

import com.nona.changeTracking.domain.model.unitofwork.Snapshot;

/**
 * 当 {@link ChangeDetector} 遇到一个没有注册相应 {@link SnapshotComparator} 的
 * {@link Snapshot} 类型时抛出的非受检异常。
 */
public class UnsupportedSnapshotTypeException extends RuntimeException {

    public UnsupportedSnapshotTypeException(final Class<? extends Snapshot> snapshotType) {
        super("No SnapshotComparator registered for snapshot type: " + snapshotType.getName());
    }
}
