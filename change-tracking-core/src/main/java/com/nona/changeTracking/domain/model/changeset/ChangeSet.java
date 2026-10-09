package com.nona.changeTracking.domain.model.changeset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 代表一次变更检测计算产出的全部对象变更集合（检测器输出）。
 * <p>
 * 这是框架最终输出的顶层值对象，只收纳有实际变化的目标（无变化的目标不产生 {@link ObjectChange}，
 * 因而 {@link #isEmpty()} 可直接表达「本次计算没有净变化」）。它提供两种只读视图：
 * <ul>
 *   <li>{@link #getAllChanges()} - 完整视图：按前序列出每个目标的全部变更（分组与原子变化），
 *       同一逻辑节点作为结果树中已建立的节点参与输出</li>
 *   <li>{@link #getLeafChanges()} - 叶子视图：按相同顺序只列出原子变化（非分组节点）</li>
 * </ul>
 * 两个视图都只做选择与遍历：不复制节点、不重新解释定位、不重建相对路径副本。定位含义在所有入口下
 * 一致（完整路径固定、相对路径相对真实包含节点、字段名与集合归属继承真实上下文），因此同一节点在
 * 两个视图中的类型、载荷与定位一致。查询是无副作用的只读操作：不修改结果、不修改快照或追踪基线。
 * <p>
 * 公开契约按值语义解释变化，不承诺不同入口或两次获取的单位元素 Java 引用身份相等；节点复用是实现的
 * 架构约束（按构造与分配证据验证），不是消费方可以依赖的接口承诺。
 *
 * @param changes 所有被追踪对象的变更列表
 */
public record ChangeSet(List<ObjectChange> changes) {

    /**
     * 紧凑构造器：非空校验并防御性复制目标变更列表。
     *
     * @param changes 包含所有对象变更的列表，不能为 null
     */
    public ChangeSet {
        Objects.requireNonNull(changes, "changes");
        for (final ObjectChange change : changes) {
            Objects.requireNonNull(change, "change");
        }
        changes = List.copyOf(changes);
    }

    /**
     * 按前序把一个目标根下的结果追加到收集列表（分组与其后代各出现一次）。
     *
     * @param changes   目标根下的结果列表
     * @param collected 收集列表
     */
    private static void collectAll(final List<Change> changes, final List<Change> collected) {
        for (final Change change : changes) {
            collected.add(change);
            if (change instanceof ContainerChange container) {
                collectAll(container.children(), collected);
            }
        }
    }

    /**
     * 按相同前序收集一个目标根下的原子变化（非分组节点）。
     *
     * @param changes   目标根下的结果列表
     * @param collected 收集列表
     */
    private static void collectLeaves(final List<Change> changes, final List<Change> collected) {
        for (final Change change : changes) {
            if (change instanceof ContainerChange container) {
                collectLeaves(container.children(), collected);
            } else {
                collected.add(change);
            }
        }
    }

    /**
     * 获取完整视图：每个目标的变更树按前序展平，分组与原子变化各出现一次。
     *
     * @return 完整变更的只读列表
     */
    public List<Change> getAllChanges() {
        if (this.changes.isEmpty()) {
            return List.of();
        }
        final List<Change> collected = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectAll(objectChange.changes(), collected);
        }
        return Collections.unmodifiableList(collected);
    }

    /**
     * 获取叶子视图：只列出原子变化，等价于从完整视图中选择非分组节点，顺序与完整视图一致。
     *
     * @return 原子变化的只读列表
     */
    public List<Change> getLeafChanges() {
        if (this.changes.isEmpty()) {
            return List.of();
        }
        final List<Change> collected = new ArrayList<>();
        for (final ObjectChange objectChange : this.changes) {
            collectLeaves(objectChange.changes(), collected);
        }
        return Collections.unmodifiableList(collected);
    }

    /**
     * 检查此变更集是否为空。
     *
     * @return 如果没有任何变更，则为 true
     */
    public boolean isEmpty() {
        return this.changes.isEmpty();
    }
}
