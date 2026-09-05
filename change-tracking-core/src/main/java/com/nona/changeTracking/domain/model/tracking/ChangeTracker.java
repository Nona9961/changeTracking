package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.capability.ComparisonStrategy;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.ObjectChange;
import com.nona.changeTracking.domain.model.snapshot.Snapshot;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.internal.snapshot.ValueNodeDeepCopier;
import com.nona.changeTracking.spi.SnapshotStrategy;

import java.util.*;

/**
 * 变更检测器：追踪领域对象的属性级变更。
 * <p>
 * 本类本质是<b>变更检测器</b>，而非经典工作单元（Unit of Work）——它不管理
 * INSERT/UPDATE/DELETE 全生命周期，只负责检测已追踪对象的属性变更（UPDATE）。
 * <p>
 * <b>三种方法的语义：</b>
 * <ul>
 *   <li>{@link #track(Object)} - 纳入追踪：为对象建立初始快照基线</li>
 *   <li>{@link #excludeNew(Object)} - 排除机制：标记为新对象，不创建快照，不生成变更</li>
 *   <li>{@link #excludeRemoved(Object)} - 排除机制：标记为已删除，不再比较，不生成变更</li>
 * </ul>
 * <p>
 * 调用 {@link #calculateChanges()} 时，只会比较已追踪对象的当前状态与初始快照，
 * 被排除的对象会被忽略。
 * <p>
 * <b>幂等视图</b>：{@link #calculateChanges()} 是无副作用的幂等视图——重复调用
 * 返回相同变更集；基线仅在 {@link #track(Object)} 时建立，如需推进基线，
 * 由调用方重新 {@link #track(Object)} 登记。
 *
 * @see TrackingCapability 追踪能力接口
 * @see ChangeSet 变更集输出
 */
public final class ChangeTracker {

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
    public ChangeTracker(final TrackingCapability<?> capability) {
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
    public void track(final Object entity) {
        Objects.requireNonNull(entity, "Cannot track a null entity.");
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
    public void excludeNew(final Object entity) {
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
    public void excludeRemoved(final Object entity) {
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
     * 导出当前追踪基线：实体 → 深拷贝快照树根节点 的不可变映射。
     * <p>
     * 对 {@link #track(Object)} 登记时刻的每个干净对象，取其快照的快照树根节点
     * （当前快照类型为 {@link ValueNodeSnapshot}），经
     * {@code ValueNodeDeepCopier}（internal 深拷贝器）深拷贝后按实体键收集——
     * 输出与源树<b>结构独立</b>（结构性节点全部重建为全新实例，叶子不可变值按引用
     * 共享，循环/共享结构保持），跨线程隔离不依赖“构建后不再修改”的不可变心智契约。
     * <p>
     * <b>幂等</b>：本方法只读 {@code cleanObjects}，不修改 tracker 内部状态——
     * 重复调用返回内容等价、结构独立的副本；导出后源 tracker 的后续追踪不影响已导出基线。
     * <p>
     * <b>空基线合法</b>：无追踪对象时返回空映射（实体数 = 0），不抛异常。
     * <p>
     * 导出的基线可直接作为 {@link #fromBaseline(TrackingCapability, BaselineSnapshot)}
     * 的输入（往返可逆），用于跨线程/跨作用域的基线恢复。
     *
     * @return 实体 → 深拷贝快照树根节点 的不可变映射（identity 键语义，与
     *         {@code cleanObjects} 一致）；空映射表示无追踪对象。
     */
    public BaselineSnapshot captureBaseline() {
        final Map<Object, ValueNode> entities = new IdentityHashMap<>();
        for (final Map.Entry<Object, Snapshot<?>> entry : this.cleanObjects.entrySet()) {
            // 类型守卫：与 calculateChangesWithCapture 的 checked cast 风格一致——
            // 快照均由同一能力单元创建，正常路径必然为 ValueNodeSnapshot；
            // 若因误用能力单元导致不匹配，得到清晰的 ClassCastException 而非静默错误。
            final ValueNodeSnapshot snapshot = (ValueNodeSnapshot) entry.getValue();
            entities.put(entry.getKey(), ValueNodeDeepCopier.deepCopy(snapshot.getSnapshotData()));
        }
        return new BaselineSnapshot(entities);
    }

    /**
     * 从导出的基线重建新的变更追踪器（静态工厂，与 {@link #captureBaseline()} 对称）。
     * <p>
     * 将 {@link BaselineSnapshot} 的全部条目<b>直接登记为基线</b>——快照包回
     * {@link ValueNodeSnapshot} 以满足比较层的类型守卫（checked cast），<b>不重新脱水</b>。
     * 与 {@link #track(Object)} 的语义对比：对<b>已修改</b>实体调用 {@code track(entity)}
     * 会以修改后状态重新脱水重建基线，与当前状态 diff 为空——变更静默丢失；
     * 跨线程/跨作用域恢复基线必须使用本工厂（异步提交侧的实体已发生业务修改）。
     * <p>
     * <b>空基线合法</b>：空 {@link BaselineSnapshot} 产出无基线的新 tracker，不抛异常。
     * <p>
     * <b>只读</b>：本工厂不修改传入的 {@link BaselineSnapshot}，同一基线可多次复用重建；
     * 重建后调用 {@link #calculateChanges()} 即得基于导入快照的完整变更集。
     *
     * @param capability 用于创建新 tracker 的追踪能力，不能为 null。
     * @param baseline   待登记的追踪基线（{@link #captureBaseline()} 的产出），不能为 null。
     * @return 已登记给定基线全部条目的新 ChangeTracker 实例。
     * @throws NullPointerException 如果 capability 或 baseline 为 null。
     */
    public static ChangeTracker fromBaseline(final TrackingCapability<?> capability, final BaselineSnapshot baseline) {
        Objects.requireNonNull(capability, "TrackingCapability cannot be null.");
        Objects.requireNonNull(baseline, "BaselineSnapshot cannot be null.");
        final ChangeTracker tracker = new ChangeTracker(capability);
        for (final Map.Entry<Object, ValueNode> entry : baseline.entities().entrySet()) {
            tracker.cleanObjects.put(entry.getKey(), new ValueNodeSnapshot(entry.getValue()));
        }
        return tracker;
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
