package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按需变更收集器（US01）：一个容器比较生命周期内按发现顺序收集子变更。
 * <p>
 * 空态不分配元素存储：仅当首次收集到实际变化时才创建元素列表；空态经 {@link #toList()}
 * 提供<b>非空</b>的空列表，供 {@link com.nona.changeTracking.domain.model.changeset.ContainerChangeNode}
 * 构造其不可变 children 列表。节点比较的零或一项结果直接交给所属容器，不为一次分发再生成
 * 单元素列表。
 */
final class ChangeAccumulator {

    /**
     * 按发现顺序收集的变更；null 表示尚未收集到任何变化（空态）。
     */
    private List<ChangeNode> changes;

    /**
     * 创建空态的变更收集器。
     */
    ChangeAccumulator() {
    }

    /**
     * 收集一个实际变化（节点比较的零或一项结果直接传入）。
     *
     * @param change 变更节点，不能为 null。
     * @throws NullPointerException 如果 change 为 null。
     */
    void add(final ChangeNode change) {
        Objects.requireNonNull(change, "change");
        if (this.changes == null) {
            this.changes = new ArrayList<>();
        }
        this.changes.add(change);
    }

    /**
     * 判断是否尚未收集到任何变化。
     *
     * @return 空态返回 true。
     */
    boolean isEmpty() {
        return this.changes == null;
    }

    /**
     * 返回按发现顺序收集的变更列表；空态返回非空的空列表。
     *
     * @return 收集到的变更列表（空态为空列表）。
     */
    List<ChangeNode> toList() {
        return this.changes == null ? List.of() : this.changes;
    }
}
