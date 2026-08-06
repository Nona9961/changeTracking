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

/**
 * 工作单元模式的实现，用于追踪领域对象的属性级变更。
 * <p>
 * 核心职责是追踪 "clean" 对象的属性变更，而非对象的生命周期。
 * <p>
 * <b>三种注册方法的语义：</b>
 * <ul>
 *   <li>{@link #registerClean(Object)} - 注册需要追踪属性变更的对象，会创建初始快照</li>
 *   <li>{@link #registerNew(Object)} - 排除机制：标记为新对象，不创建快照，不生成变更</li>
 *   <li>{@link #registerRemoved(Object)} - 排除机制：标记为已删除，不再比较，不生成变更</li>
 * </ul>
 * <p>
 * 调用 {@link #calculateChanges()} 时，只会比较 cleanObjects 中的对象，
 * newObjects 和 removedObjects 中的对象会被忽略。
 *
 * @see TrackingCapability 追踪能力接口
 * @see ChangeSet 变更集输出
 */
public final class UnitOfWork {

    private final Map<Object, Snapshot<?>> cleanObjects = new IdentityHashMap<>();
    private final Set<Object> newObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> removedObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    private final TrackingCapability<?> capability;

    /**
     * 创建一个新的工作单元实例。
     *
     * @param capability 用于创建快照和比较变更的追踪能力，不能为 null。
     * @throws NullPointerException 如果 capability 为 null。
     */
    public UnitOfWork(final TrackingCapability<?> capability) {
        this.capability = Objects.requireNonNull(capability, "TrackingCapability cannot be null.");
    }

    /**
     * 注册一个需要追踪属性变更的对象。
     * <p>
     * 会立即创建对象的初始快照，后续调用 {@link #calculateChanges()} 时
     * 会将当前状态与初始快照进行比较，生成变更记录。
     * <p>
     * 如果对象已被追踪（在任何集合中），则此调用无效。
     *
     * @param entity 要追踪的对象，不能为 null。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public void registerClean(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null clean entity.");
        if (isTracking(entity)) {
            return;
        }
        final Snapshot<?> initialSnapshot = this.capability.getSnapshotStrategy().createSnapshot(entity);
        this.cleanObjects.put(entity, initialSnapshot);
    }

    /**
     * 将对象标记为新建（排除机制）。
     * <p>
     * 新建对象不会创建快照，也不会在 {@link #calculateChanges()} 中生成任何变更。
     * 这是一种排除机制，用于标记不需要追踪变更的新对象。
     * <p>
     * 如果对象已被追踪（在任何集合中），则此调用无效。
     *
     * @param entity 要标记为新建的对象，不能为 null。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public void registerNew(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null new entity.");
        if (isTracking(entity)) {
            return;
        }
        this.newObjects.add(entity);
    }

    /**
     * 将对象标记为已删除（排除机制）。
     * <p>
     * 已删除对象会从 cleanObjects 和 newObjects 中移除，
     * 不会在 {@link #calculateChanges()} 中生成任何变更。
     * 这是一种排除机制，用于停止追踪已删除的对象。
     * <p>
     * 如果对象已在 removedObjects 中，则此调用无效。
     *
     * @param entity 要标记为已删除的对象，不能为 null。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public void registerRemoved(final Object entity) {
        Objects.requireNonNull(entity, "Cannot register a null removed entity.");
        if (this.removedObjects.contains(entity)) {
            return;
        }
        this.cleanObjects.remove(entity);
        this.newObjects.remove(entity);
        this.removedObjects.add(entity);
    }

    /**
     * 计算所有 clean 对象的变更。
     * <p>
     * 遍历 cleanObjects 中的所有对象，将当前状态与初始快照进行比较，
     * 生成包含所有变更的 {@link ChangeSet}。
     * <p>
     * newObjects 和 removedObjects 中的对象会被忽略，不生成任何变更。
     *
     * @return 包含所有检测到变更的 ChangeSet。
     */
    public ChangeSet calculateChanges() {
        return calculateChangesWithCapture(this.capability);
    }

    /**
     * 检查对象是否已被追踪。
     *
     * @param entity 要检查的对象。
     * @return 如果对象在任何追踪集合中，返回 true。
     */
    private boolean isTracking(final Object entity) {
        return this.cleanObjects.containsKey(entity)
                || this.newObjects.contains(entity)
                || this.removedObjects.contains(entity);
    }

    /**
     * 一个私有的、泛型的辅助方法，其目的是“捕获”构造函数中传入的通配符 ? 的具体类型，
     * 从而在方法内部可以进行完全类型安全的操作。
     * <p>
     * 新快照由 {@link SnapshotStrategy#createSnapshot(Object)} 直接产出，编译期即为 {@code S} 类型；
     * 旧快照取自 {@code cleanObjects}（存储为 {@code Snapshot<?>}），
     * 通过 {@link ComparisonStrategy#getSupportedSnapshotType()} 的 checked cast 做显式类型守卫——
     * 快照均由同一能力单元创建，正常路径必然兼容；若因误用能力单元导致不匹配，
     * 会得到清晰的 {@link ClassCastException} 而非堆污染。
     *
     * @param specificCapability 一个具有具体泛型类型 S 的能力实例。
     * @param <S>                被捕获的、具体的 Snapshot 类型。
     * @return 计算出的变更集。
     */
    private <S extends Snapshot<?>> ChangeSet calculateChangesWithCapture(final TrackingCapability<S> specificCapability) {
        final List<ObjectChange> changes = new ArrayList<>();
        final SnapshotStrategy<S> snapshotStrategy = specificCapability.getSnapshotStrategy();
        final ComparisonStrategy<S> comparisonStrategy = specificCapability.getComparisonStrategy();
        final Class<S> supportedSnapshotType = comparisonStrategy.getSupportedSnapshotType();

        for (final Map.Entry<Object, Snapshot<?>> entry : this.cleanObjects.entrySet()) {
            final Object entity = entry.getKey();
            final S oldSnapshot = supportedSnapshotType.cast(entry.getValue());
            final S newSnapshot = snapshotStrategy.createSnapshot(entity);

            final ChangeNode changeTree = comparisonStrategy.compare(oldSnapshot, newSnapshot);

            if (changeTree instanceof ContainerChangeNode container && !container.children().isEmpty()) {
                changes.add(new ObjectChange(entity, changeTree));
            }
        }
        return new ChangeSet(changes);
    }
}
