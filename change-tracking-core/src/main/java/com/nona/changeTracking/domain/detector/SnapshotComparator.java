package com.nona.changeTracking.domain.detector;

import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.unitofwork.Snapshot;

import java.util.List;

/**
 * 一个策略接口，定义了如何比较一个特定类型的 {@link Snapshot} 与对象的当前状态。
 * <p>
 * 这是 {@link ChangeDetector} 用来解耦其核心逻辑与具体快照类型比较算法的插件点。
 *
 * @param <S> 此比较器能够处理的 {@link Snapshot} 的具体类型。
 */
public interface SnapshotComparator<S extends Snapshot<?>> {

    /**
     * 返回此比较器能够处理的 Snapshot 的 Class 对象。
     * <p>
     * 这个方法是必需的，以便 {@link ChangeDetectorBuilder} 能够安全地、
     * 无需反射地将比较器与其支持的 Snapshot 类型关联起来。
     *
     * @return Snapshot 类型的 Class 对象。
     */
    Class<S> getSupportedSnapshotType();

    /**
     * 比较快照和对象的当前状态，找出所有发生变化的字段。
     *
     * @param snapshot      要比较的快照实例。
     * @param currentObject 对象的当前实例。
     * @return 一个包含所有 {@link FieldChange} 的列表。如果没有任何变化，则返回一个空列表。
     */
    List<FieldChange> compare(S snapshot, Object currentObject);
}
