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
 * <b>两种方法的语义：</b>
 * <ul>
 *   <li>{@link #track(Object)} - 纳入追踪：为对象建立初始快照基线</li>
 *   <li>{@link #stopTracking(Object)} - 停止追踪：将对象移出追踪集合，不再参与变更计算
 *       （幂等；停止后可重新 {@link #track(Object)} 恢复）</li>
 * </ul>
 * <p>
 * 调用 {@link #calculateChanges()} 时，只会比较已追踪对象的当前状态与初始快照；
 * 停止追踪或从未追踪的对象不参与比较、不产生变更。
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

    private final TrackingCapability<?> capability;

    /**
     * 创建一个新的变更检测器实例。
     *
     * @param capability 用于创建快照和比较变更的追踪能力，不能为 null。
     * @throws NullPointerException 如果 capability 为 null。
     */
    public ChangeTracker(final TrackingCapability<?> capability) {
        this.capability = Objects.requireNonNull(capability, "TrackingCapability cannot be null.");
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
     * 注册一个需要追踪属性变更的对象。
     * <p>
     * 会立即创建对象的初始快照，后续调用 {@link #calculateChanges()} 时
     * 会将当前状态与初始快照进行比较，生成变更记录。
     * <p>
     * 如果对象已被追踪，则此调用无效（幂等）。
     *
     * @param entity 要追踪的对象，不能为 null。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public void track(final Object entity) {
        Objects.requireNonNull(entity, "Cannot track a null entity.");
        if (this.cleanObjects.containsKey(entity)) {
            return;
        }
        final Snapshot<?> initialSnapshot = this.capability.getSnapshotStrategy().createSnapshot(entity);
        this.cleanObjects.put(entity, initialSnapshot);
    }

    /**
     * 停止追踪指定对象：将其从追踪集合中移除，此后不再参与任何变更计算。
     * <p>
     * <b>契约（三件套）：</b>
     * <ul>
     *   <li><b>幂等</b>：对未追踪对象调用无副作用（不抛异常、不改变任何状态）；
     *       对已停止对象重复调用同样无效果——停止语义以“目标状态达成”（该对象不参与
     *       比较）定义，非“执行一次删除”，重复调用安全。</li>
     *   <li><b>可恢复</b>：停止后允许再次 {@link #track(Object)}——以调用时刻的当前
     *       状态重新建立基线并恢复追踪（与 track 对从未追踪对象的首次登记语义一致）；
     *       停止到重新 track 之间发生的修改，因重新登记以当前状态为基线而不产生变更
     *       （与 track 对已修改实体的既有语义一致）。</li>
     *   <li><b>null 处理</b>：entity 为 null 时抛出 {@link NullPointerException}
     *       （与 {@link #track(Object)} 同约定）。</li>
     * </ul>
     * <p>
     * <b>停止时点语义</b>：调用即生效——即使停止前对象已发生修改（尚未计算变更），
     * 停止后 {@link #calculateChanges()} 也不会再为该对象产生任何 {@code ObjectChange}；
     * 等价于该对象从未被追踪。停止不影响其他已追踪对象。
     * <p>
     * 与导出基线的关系：停止后 {@link #captureBaseline()} 不再包含该实体
     * （基线视图只反映当前追踪集合）。
     *
     * @param entity 要停止追踪的对象，不能为 null。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public void stopTracking(final Object entity) {
        Objects.requireNonNull(entity, "Cannot stop tracking a null entity.");
        this.cleanObjects.remove(entity);
    }

    /**
     * 计算所有 clean 对象的变更。
     * <p>
     * 遍历 cleanObjects 中的所有对象，将当前状态与初始快照进行比较，
     * 生成包含所有变更的 {@link ChangeSet}。
     * <p>
     * 未追踪（停止追踪或从未追踪）的对象不在 cleanObjects 中，不参与比较、不生成任何变更。
     *
     * @return 包含所有检测到变更的 ChangeSet。
     */
    public ChangeSet calculateChanges() {
        return calculateChangesWithCapture(this.capability);
    }

    /**
     * 只针对指定实体计算变更（按根取变更）。
     * <p>
     * 与 {@link #calculateChanges()} 共享同一比较链路与产出过滤条件——本方法只对指定实体做
     * 基线比较，结果与全局计算按 {@code ObjectChange.target}（identity）过滤<b>等价</b>；
     * <b>不</b>为其他已追踪对象执行快照脱水与比较。
     * <p>
     * <b>空集语义</b>：未追踪实体（含已 {@link #stopTracking(Object)} 的）与已追踪但无变更
     * 的实体均返回空 {@link ChangeSet}（不抛异常）。
     * <p>
     * <b>幂等视图</b>：与 {@link #calculateChanges()} 一致——无副作用、不更新基线，
     * 重复调用返回相同变更集。
     *
     * @param entity 要计算变更的实体，不能为 null。
     * @return 该实体的单元素 ChangeSet（含完整 changeTree）；无变更/未追踪时为空 ChangeSet。
     * @throws NullPointerException 如果 entity 为 null。
     */
    public ChangeSet calculateChangesFor(final Object entity) {
        Objects.requireNonNull(entity, "Cannot calculate changes for a null entity.");
        if (!this.cleanObjects.containsKey(entity)) {
            return new ChangeSet(List.of());
        }
        return calculateChangeForWithCapture(entity, this.capability);
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
        for (final Object entity : this.cleanObjects.keySet()) {
            changes.addAll(calculateChangeForWithCapture(entity, specificCapability).changes());
        }
        return new ChangeSet(changes);
    }

    private <S extends Snapshot<?>> ChangeSet calculateChangeForWithCapture(final Object entity, final TrackingCapability<S> specificCapability) {
        final SnapshotStrategy<S> snapshotStrategy = specificCapability.getSnapshotStrategy();
        final ComparisonStrategy<S> comparisonStrategy = specificCapability.getComparisonStrategy();
        final Class<S> supportedSnapshotType = comparisonStrategy.getSupportedSnapshotType();
        final S oldSnapshot = supportedSnapshotType.cast(this.cleanObjects.get(entity));
        final S newSnapshot = snapshotStrategy.createSnapshot(entity);
        final ChangeNode changeTree = comparisonStrategy.compare(oldSnapshot, newSnapshot);
        if (changeTree instanceof ContainerChangeNode container && !container.children().isEmpty()) {
            return new ChangeSet(List.of(new ObjectChange(entity, changeTree)));
        }
        return new ChangeSet(List.of());
    }
}
