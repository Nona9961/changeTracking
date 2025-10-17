package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.util.*;

public final class UnitOfWork {

    private final Map<Object, Snapshot<?>> cleanObjects = new IdentityHashMap<>();
    private final Set<Object> newObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> removedObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    private final TrackingCapability<?> capability;

    public UnitOfWork(final TrackingCapability<?> capability) {
        this.capability = Objects.requireNonNull(capability, "TrackingCapability cannot be null.");
    }

    public void registerClean(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null clean entity.");
        if (isTracking(entity)) return;
        final Snapshot<?> initialSnapshot = this.capability.getSnapshotStrategy().createSnapshot(entity);
        this.cleanObjects.put(entity, initialSnapshot);
    }

    public void registerNew(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null new entity.");
        if (isTracking(entity)) return;
        this.newObjects.add(entity);
    }

    public void registerRemoved(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null removed entity.");
        if (this.removedObjects.contains(entity)) return;
        this.cleanObjects.remove(entity);
        this.newObjects.remove(entity);
        this.removedObjects.add(entity);
    }

    public ChangeSet calculateChanges() {
        return calculateChangesWithCapture(this.capability);
    }

    private boolean isTracking(final Object entity) {
        return this.cleanObjects.containsKey(entity)
                || this.newObjects.contains(entity)
                || this.removedObjects.contains(entity);
    }

    /**
     * 一个私有的、泛型的辅助方法，其目的是“捕获”构造函数中传入的通配符 ? 的具体类型，
     * 从而在方法内部可以进行完全类型安全的操作。
     *
     * @param specificCapability 一个具有具体泛型类型 S 的能力实例。
     * @param <S>                被捕获的、具体的 Snapshot 类型。
     * @return 计算出的变更集。
     */
    @SuppressWarnings("unchecked")
    private <S extends Snapshot<?>> ChangeSet calculateChangesWithCapture(final TrackingCapability<S> specificCapability) {
        final List<ObjectChange> changes = new ArrayList<>();
        final SnapshotStrategy snapshotStrategy = specificCapability.getSnapshotStrategy();
        final ComparisonStrategy<S> comparisonStrategy = specificCapability.getComparisonStrategy();

        for (final Map.Entry<Object, Snapshot<?>> entry : this.cleanObjects.entrySet()) {
            final Object entity = entry.getKey();
            final Snapshot<?> oldSnapshot = entry.getValue();
            final Snapshot<?> newSnapshot = snapshotStrategy.createSnapshot(entity);

            // **【核心修正点】**: 移除所有运行时检查。
            // 我们完全信任 TrackingCapability<S> 的契约，即它提供的策略和快照是类型兼容的。
            // 这个强制转换是基于架构性信任的，因此是安全的。
            final ChangeNode changeTree = comparisonStrategy.compare((S) oldSnapshot, (S) newSnapshot);

            if (changeTree instanceof ContainerChangeNode container && !container.children().isEmpty()) {
                changes.add(new ObjectChange(entity, changeTree));
            }
        }
        return new ChangeSet(changes);
    }
}
