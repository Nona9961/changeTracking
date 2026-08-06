package com.nona.changeTracking.domain.model.snapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 表示集合类型的快照节点。
 * <p>
 * 用于表示 {@link java.util.Collection} 和 {@link java.util.Map}（作为 Entry 集合）的快照。
 * 集合中的每个元素递归表示为 {@link ValueNode}。
 * <p>
 * 不可变契约（D11）：
 * <ul>
 *   <li>本类为 final class，内部元素列表不对外暴露任何集合引用——外部无法获取引用，
 *       写操作在编译级不可能</li>
 *   <li>构造器不拷贝传入的列表（List 输入）：快照构建采用「先登记后填充」流程（支持循环引用），
 *       构造期拷贝会静默丢失填充的元素；调用方必须在构建完成后不再修改传入的列表</li>
 *   <li>{@link #equals(Object)} / {@link #hashCode()} / {@link #toString()} 为内容语义
 *       （元素顺序敏感），并通过 IdentityHashMap 访问集防止循环引用导致的栈溢出</li>
 * </ul>
 */
public final class CollectionNode implements ValueNode {

    /** 集合中所有元素的 ValueNode 表示，保持迭代顺序。 */
    private final List<ValueNode> items;

    /**
     * 创建一个 CollectionNode。
     * <p>
     * 注意：List 输入不拷贝——「先登记后填充」构建流程依赖持有原引用；
     * 非 List 输入（如 Set）转换为 ArrayList 以维持确定性的迭代顺序。
     *
     * @param items 集合中所有元素的 ValueNode 表示。
     */
    public CollectionNode(final Collection<ValueNode> items) {
        if (items instanceof List<ValueNode> list) {
            this.items = list;
        } else {
            this.items = new ArrayList<>(items);
        }
    }

    /**
     * 返回集合元素数量。
     *
     * @return 元素数量。
     */
    public int size() {
        return this.items.size();
    }

    /**
     * 按索引取元素。
     *
     * @param index 元素索引（从 0 开始）。
     * @return 指定索引处的 ValueNode。
     * @throws IndexOutOfBoundsException 索引越界时。
     */
    public ValueNode item(final int index) {
        return this.items.get(index);
    }

    /**
     * 只读遍历所有元素（保持迭代顺序）。
     *
     * @param consumer 接收元素的消费者，不能为 null。
     */
    public void forEachItem(final Consumer<ValueNode> consumer) {
        this.items.forEach(consumer);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：元素列表逐项内容相等（顺序敏感）。
     * 通过 IdentityHashMap 访问集终止循环引用递归（已在访问集中的节点视为相等）。
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CollectionNode that)) {
            return false;
        }
        return equals(that, new IdentityHashMap<>());
    }

    /**
     * 递归比较两个 CollectionNode 的元素内容。
     * <p>
     * 包可见：被 {@link ObjectNode} 的递归比较调用（同包互访），
     * 保证 ObjectNode 与 CollectionNode 交叉引用时仍能正确终止。
     *
     * @param that    待比较的 CollectionNode。
     * @param visited 当前递归路径上的节点访问集（IdentityHashMap，身份比较）。
     * @return 内容相等返回 true。
     */
    boolean equals(final CollectionNode that, final IdentityHashMap<ValueNode, Boolean> visited) {
        if (this.items.size() != that.items.size()) {
            return false;
        }
        for (int index = 0; index < this.items.size(); index++) {
            if (!ObjectNode.valueEquals(this.items.get(index), that.items.get(index), visited)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：元素内容 hashCode 的顺序组合（与 List 语义一致）。
     * 循环引用路径上的节点返回 0，保证相等对象 hashCode 一致且不栈溢出。
     */
    @Override
    public int hashCode() {
        return hashCode(new IdentityHashMap<>());
    }

    /**
     * 递归计算元素内容 hashCode。
     *
     * @param visited 当前递归路径上的节点访问集。
     * @return 本节点的内容 hashCode。
     */
    int hashCode(final IdentityHashMap<ValueNode, Boolean> visited) {
        if (visited.containsKey(this)) {
            return 0;
        }
        visited.put(this, Boolean.TRUE);
        try {
            int result = 1;
            for (final ValueNode item : this.items) {
                result = 31 * result + ObjectNode.valueHashCode(item, visited);
            }
            return result;
        } finally {
            visited.remove(this);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 内容语义：展示元素列表；循环引用路径上的节点以 {@code (cycle)} 表示。
     */
    @Override
    public String toString() {
        return toString(new IdentityHashMap<>());
    }

    /**
     * 递归生成内容字符串。
     *
     * @param visited 当前递归路径上的节点访问集。
     * @return 本节点的内容字符串。
     */
    String toString(final IdentityHashMap<ValueNode, Boolean> visited) {
        if (visited.containsKey(this)) {
            return "(cycle)";
        }
        visited.put(this, Boolean.TRUE);
        try {
            final StringBuilder sb = new StringBuilder("CollectionNode{items=[");
            boolean first = true;
            for (final ValueNode item : this.items) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(ObjectNode.valueToString(item, visited));
            }
            return sb.append("]}").toString();
        } finally {
            visited.remove(this);
        }
    }
}
