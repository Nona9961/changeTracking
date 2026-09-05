package com.nona.changeTracking.domain.model.tracking;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 追踪基线的不可变视图：实体 → 深拷贝快照树根节点 的映射。
 * <p>
 * 基线是 {@link ChangeTracker#track(Object)} 登记时刻的“干净状态”。对<b>已修改</b>实体
 * 调用 {@code track(entity)} 会以修改后状态重新脱水重建基线——与当前状态 diff 为空，
 * 变更静默丢失；跨线程/跨作用域恢复基线必须使用静态工厂
 * {@code ChangeTracker.fromBaseline(capability, baseline)}（与导出 {@code captureBaseline()}
 * 对称、往返可逆）。
 * <p>
 * <b>键语义（identity）</b>：实体键与 {@code ChangeTracker.cleanObjects} 的
 * {@link IdentityHashMap} 语义一致——equals() 相等的不同实例仍是不同条目，查找也按
 * 实例身份进行。注意不可用 {@link Map#copyOf} 包装：其 equals 语义会把 equals 相等的
 * 不同实体合并为一条，基线条目静默丢失。
 * <p>
 * <b>值语义（深拷贝）</b>：值为实体根节点的深拷贝快照树——结构性节点
 * （{@link com.nona.changeTracking.domain.model.snapshot.ObjectNode} /
 * {@link com.nona.changeTracking.domain.model.snapshot.CollectionNode} /
 * {@link com.nona.changeTracking.domain.model.snapshot.ArrayNode}）全部重建，
 * 与源树零共享结构性节点；叶子（{@link com.nona.changeTracking.domain.model.snapshot.PrimitiveNode}
 * / {@link com.nona.changeTracking.domain.model.snapshot.NullNode}，值不可变）按引用共享；
 * 循环/共享结构保持（同一源节点复制为同一新实例）。深拷贝由
 * {@code com.nona.changeTracking.internal.snapshot.ValueNodeDeepCopier} 完成，
 * 本类不持有业务对象引用。
 * <p>
 * <b>不可变性</b>：紧凑构造器将传入映射归一化为「新 {@link IdentityHashMap} 副本 +
 * 不可修改包装」——无论从哪条构造路径进入，内部映射都不可修改，不暴露任何修改途径；
 * 调用方持有的原映射与实例内部映射无共享。空基线合法（无追踪对象时为空映射，
 * 导出与重建均不抛异常）。
 * <p>
 * <b>equals 语义（实例身份）</b>：record 的 {@code equals} 委托给内部映射
 * （{@link IdentityHashMap}）的 equals——其对键与值均按<b>实例身份</b>比较
 * （JDK 实现经 {@code containsMapping} 引用比较），因此内容等价但结构独立的
 * 两次导出（{@code captureBaseline()} 产生的深拷贝值树必为不同实例）equals 为 false。
 * 本类型是基线传输/重建容器，equals 不用于内容等价比较；需要内容等价时逐条目
 * 比较值树（{@code ValueNode} 的 equals 为内容语义且 cycle-safe，可直接断言）。
 * {@code hashCode} 按条目的键哈希与值内容哈希组合计算（{@link IdentityHashMap} 策略），
 * 同一实例映射恒得同一哈希，与 equals 无契约冲突。
 *
 * @param entities 实体 → 快照树根节点的不可修改映射（identity 键语义）；
 *                 实体与快照根节点均非 null（null 值以 NullNode 表示）。
 */
public record BaselineSnapshot(Map<Object, ValueNode> entities) {

    /**
     * 紧凑构造器：归一化不可变 + identity 键语义。
     * <p>
     * 复制为新的 {@link IdentityHashMap}（保持键的实例身份语义——与
     * {@code ChangeTracker.cleanObjects} 一致），再以不可修改包装封闭：
     * 任何调用方（含原映射持有者）都无法修改实例内部状态。
     *
     * @param entities 实体 → 快照树根节点的映射，不能为 null，且不得含 null 键/值。
     * @throws NullPointerException 如果 entities 为 null。
     */
    public BaselineSnapshot {
        Objects.requireNonNull(entities, "Baseline entities cannot be null.");
        entities = Collections.unmodifiableMap(new IdentityHashMap<>(entities));
    }
}