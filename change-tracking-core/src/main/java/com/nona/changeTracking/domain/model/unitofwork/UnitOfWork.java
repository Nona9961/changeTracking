package com.nona.changeTracking.domain.model.unitofwork;

import com.nona.changeTracking.domain.detector.ChangeDetector;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.util.*;

/**
 * 代表一个工作单元的聚合根。
 * <p>
 * 它负责在一个业务事务的边界内，追踪领域对象的生命周期状态（新、干净、脏、已移除），
 * 并在操作完成后计算出所有变更。
 * <p>
 * 这个类是有状态的，每个实例代表一个独立的、隔离的追踪会话。
 * 它不是线程安全的，应在单个线程的上下文中使用。
 */
public final class UnitOfWork {

    private final SnapshotStrategy snapshotStrategy;
    private final ChangeDetector changeDetector;

    // 使用 IdentityHashMap 来确保通过对象引用（而非 equals/hashCode）来唯一标识对象。
    // 这对于可变实体至关重要。
    private final Map<Object, Snapshot<?>> cleanObjects = new IdentityHashMap<>();
    private final Set<Object> newObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> removedObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * 构造一个新的 UnitOfWork 实例。
     *
     * @param snapshotStrategy 用于创建对象快照的策略。
     * @param changeDetector   用于检测对象变更的服务。
     */
    public UnitOfWork(final SnapshotStrategy snapshotStrategy, final ChangeDetector changeDetector) {
        this.snapshotStrategy = Objects.requireNonNull(snapshotStrategy, "SnapshotStrategy cannot be null.");
        this.changeDetector = Objects.requireNonNull(changeDetector, "ChangeDetector cannot be null.");
    }

    /**
     * 注册一个新创建的、持久化层中还不存在的对象。
     *
     * @param entity 要追踪的新对象。
     */
    public void registerNew(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null entity.");
        // 一个对象不能既是新的又是干净的/已移除的。
        this.cleanObjects.remove(entity);
        this.removedObjects.remove(entity);
        this.newObjects.add(entity);
    }

    /**
     * 注册一个从持久化层加载的、已存在的对象，并开始追踪其变更。
     *
     * @param entity 要追踪的已存在对象。
     */
    public void registerClean(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null entity.");
        // 如果对象已经是干净的，则无需重复注册。
        if (this.cleanObjects.containsKey(entity)) {
            return;
        }
        // 一个对象不能既是干净的又是新的/已移除的。
        this.newObjects.remove(entity);
        this.removedObjects.remove(entity);

        final Snapshot<?> snapshot = this.snapshotStrategy.createSnapshot(entity);
        this.cleanObjects.put(entity, snapshot);
    }

    /**
     * 标记一个对象为待移除。
     *
     * @param entity 要标记为移除的对象。
     */
    public void registerRemoved(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null entity.");
        this.removedObjects.add(entity);
    }

    /**
     * 计算自追踪开始以来发生的所有变更，并生成一个变更集。
     * <p>
     * 这个方法是幂等的，多次调用会返回相同的结果，但可能会消耗计算资源。
     *
     * @return 一个包含所有已识别变更的 {@link ChangeSet}。
     */
    public ChangeSet calculateChanges() {
        final List<Object> finalListNew = new ArrayList<>();
        final List<ObjectChange> finalListDirty = new ArrayList<>();
        final List<Object> finalListRemoved = new ArrayList<>(this.removedObjects);

        // 处理“新”对象
        for (final Object newObject : this.newObjects) {
            if (!this.removedObjects.contains(newObject)) {
                finalListNew.add(newObject);
            }
        }

        // 处理“干净”对象，检测它们是否变“脏”
        for (final Map.Entry<Object, Snapshot<?>> entry : this.cleanObjects.entrySet()) {
            final Object entity = entry.getKey();
            final Snapshot<?> snapshot = entry.getValue();

            // 如果一个干净的对象被标记为移除，它只应出现在移除列表中。
            if (this.removedObjects.contains(entity)) {
                continue;
            }

            this.changeDetector.detectChanges(entity, snapshot)
                    .ifPresent(finalListDirty::add);
        }

        return ChangeSet.of(finalListNew, finalListDirty, finalListRemoved);
    }
}
