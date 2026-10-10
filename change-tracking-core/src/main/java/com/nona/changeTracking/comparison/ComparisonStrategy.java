package com.nona.changeTracking.comparison;

import com.nona.changeTracking.change.Change;
import com.nona.changeTracking.snapshot.Snapshot;

import java.util.List;

/**
 * 定义了比较两个快照以产出统一变更结果的策略接口。
 * <p>
 * 这是一个核心扩展点，允许框架支持不同格式快照的比较逻辑（如基于 ValueNode 树、JSON 文档、Kryo 二进制等）。
 * 策略负责判断变化并建立完整定位：产出的每个 {@link Change} 都携带定位对象与载荷，子结果直接交由
 * 分组变更持有，不产出额外的中间节点层次。
 *
 * @param <S> 此策略能够处理的 {@link Snapshot} 的具体类型。
 */
public interface ComparisonStrategy<S extends Snapshot<?>> {

    /**
     * 返回此比较策略能够处理的 {@link Snapshot} 的具体 Class 对象。
     * 这用于在运行时进行类型安全的策略分发。
     *
     * @return 支持的 Snapshot 类型的 Class。
     */
    Class<S> getSupportedSnapshotType();

    /**
     * 比较一个旧快照和一个新快照，产出被追踪目标根下的变更结果列表。
     * <p>
     * 结果契约：
     * <ul>
     *   <li>返回<b>非 null</b> 的只读列表；无变化返回空列表，不返回 {@code null}，也不产出包装根节点；</li>
     *   <li>真实根值变化以空路径原子变化表达，直接出现在列表中；</li>
     *   <li>结果列表元素为目标根下的顶层变更（原子变化或变更分组），分组不空且子结果定位处于其包含结构下。</li>
     * </ul>
     * 违反上述契约的返回（如 {@code null}）由变更检测器拒绝，不能当作空结果处理。
     *
     * @param oldSnapshot 代表对象先前状态的快照。
     * @param newSnapshot 代表对象当前状态的快照。
     * @return 目标根下的变更结果列表，无变化时为空列表。
     */
    List<Change> compare(S oldSnapshot, S newSnapshot);
}
