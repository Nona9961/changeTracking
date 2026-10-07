package com.nona.changeTracking.domain.model.changeset;

import java.util.List;
import java.util.Objects;

/**
 * 表示单个被追踪对象的整体变更：该目标相对于基线的净差异组织根。
 * <p>
 * 结果组织边界：本类型绑定追踪目标身份，并直接持有<b>目标根下</b>的非空结果列表——没有额外的人工根
 * 容器包装；比较策略产出空列表即无变化，此时不创建本对象。结果列表元素可以是原子变化（含真实根值
 * 变化产生的空路径原子变化）或顶层变更分组。
 * <p>
 * 本类型持有活动目标引用，不宣称深不可变；结果列表本身防御性复制并只读。
 *
 * @param target  被追踪的、发生变更的对象实例
 * @param changes 目标根下的变更列表，非空
 */
public record ObjectChange(Object target, List<Change> changes) {

    /**
     * 紧凑构造器：拒绝空目标与空结果列表，拒绝非法顶层结果（空路径分组），并防御性复制结果列表。
     *
     * @param target  被追踪的、发生变更的对象实例
     * @param changes 目标根下的变更列表，非空
     */
    public ObjectChange {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("A tracked object result must hold at least one change.");
        }
        for (final Change change : changes) {
            Objects.requireNonNull(change, "change");
            if (change instanceof ContainerChange && change.fullPath().isEmpty()) {
                throw new IllegalArgumentException(
                        "An artificial root container must not be located at the empty root path.");
            }
        }
        changes = List.copyOf(changes);
    }
}
