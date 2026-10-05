package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeNode;
import com.nona.changeTracking.domain.model.changeset.ContainerChangeNode;
import com.nona.changeTracking.domain.model.changeset.FieldChangeNode;
import com.nona.changeTracking.domain.model.changeset.ItemAddedNode;
import com.nona.changeTracking.domain.model.changeset.ItemRemovedNode;
import com.nona.changeTracking.domain.model.changeset.ObjectFieldChangeNode;
import com.nona.changeTracking.domain.model.snapshot.ArrayNode;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;

import java.util.Objects;

/**
 * 基于 {@link ValueNode} 树结构的快照比较策略实现。
 * <p>
 * 此策略通过递归比较两个 {@link ValueNodeSnapshot} 的树结构，
 * 生成描述所有差异的 {@link ChangeNode} 变更树。
 * <p>
 * 比较算法采用双层递归设计：
 * <ul>
 *   <li>{@code diffNode} - 高层方法，负责分发与变更分类</li>
 *   <li>{@code diffChildren} - 低层方法，负责遍历与收集</li>
 * </ul>
 * <p>
 * <b>原地优化</b>：一次 {@code compare} 创建独立的
 * {@link ComparisonContext} 与 {@link ChangeAccumulator}。路径不再以字符串参数逐层拼接，
 * 而由上下文承载路径段栈并按需生成；对象字段不再建立字段名并集，集合项经
 * {@link CollectionMatchIndex} 有序匹配；子变更按发现顺序收入
 * {@link ChangeAccumulator}，零或一项结果直接交给所属容器。既有匹配规则、输出顺序、
 * 变更类型、载荷与循环终止语义保持不变。
 * <p>
 * <b>安全无变更复用</b>：容器节点对在递归前对单表三态节点对状态做<b>一次查询</b>，
 * 返回 false 即直接返回——该 false 同时表达「循环截断」与「已完成且无变更的复用命中」；返回 true
 * 才递归子节点，并在退出时把「无变更且期间未发生循环截断」写为可复用状态。有变化、依赖截断或
 * 异常退出的节点对不可复用，仍沿各条路径生成必要输出；根节点保留原来的直接展开方式，不登记根节点对。
 * <p>
 * 集合项匹配基于 {@link ObjectNode#identifier()} 业务标识符，
 * 允许检测集合中项的新增、删除和修改。
 */
public class ValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<ValueNodeSnapshot> getSupportedSnapshotType() {
        return ValueNodeSnapshot.class;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 比较两个 ValueNode 快照，生成变更树。
     * 返回的根节点是一个 {@link com.nona.changeTracking.domain.model.changeset.ContainerChangeNode}，
     * 包含所有检测到的变更。根节点保留原来的直接展开方式，不登记根节点对。
     */
    @Override
    public ChangeNode compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
        final ComparisonContext context = new ComparisonContext();
        final ChangeAccumulator accumulator = new ChangeAccumulator();
        diffRoot(oldSnapshot.getSnapshotData(), newSnapshot.getSnapshotData(), context, accumulator);
        return new ContainerChangeNode("", accumulator.toList());
    }

    /**
     * 根节点分发：容器对（O↔O / C↔C）展开子节点，其他组合走 {@link #diffNode} 叶子级 dispatch。
     * <p>
     * 根节点可能是任意 {@link ValueNode}（如快照根直接是数组/基本值），
     * 与嵌套节点一样需要完整的 dispatch 表（A↔A 内容比较、跨类型 ObjectFieldChange 等）。
     *
     * @param oldNode     旧根节点。
     * @param newNode     新根节点。
     * @param context     单次比较上下文（路径段栈与节点对状态表）。
     * @param accumulator 根层变更收集器。
     */
    private void diffRoot(final ValueNode oldNode, final ValueNode newNode,
                          final ComparisonContext context, final ChangeAccumulator accumulator) {
        final boolean bothObject = oldNode instanceof ObjectNode && newNode instanceof ObjectNode;
        final boolean bothCollection = oldNode instanceof CollectionNode && newNode instanceof CollectionNode;
        if (bothObject || bothCollection) {
            diffChildren(oldNode, newNode, context, accumulator);
            return;
        }
        diffNode(oldNode, newNode, context, accumulator);
    }

    /**
     * 高层方法：负责分发与变更分类，把零或一项结果直接交给所属容器。
     * <p>
     * dispatch 表：
     * <ul>
     *   <li>P↔P / P↔N / N↔P（基本值之间）→ {@link com.nona.changeTracking.domain.model.changeset.FieldChangeNode}（业务值可得）</li>
     *   <li>A↔A（数组之间）→ 内容相等=无变更（{@link com.nona.changeTracking.domain.model.snapshot.ArrayNode#equals} 已是内容语义）；
     *       不等（含顺序变）→ FieldChangeNode（载荷为数组实例，消费方可强转）</li>
     *   <li>O↔O / C↔C（容器同类型）→ 递归子节点，有变更则包裹在
     *       {@link com.nona.changeTracking.domain.model.changeset.ContainerChangeNode} 中</li>
     *   <li>其余组合（容器/数组参与的跨类型变化）→
     *       {@link com.nona.changeTracking.domain.model.changeset.ObjectFieldChangeNode}（原样携带 ValueNode）</li>
     *   <li>N↔N / 同实例 → 无变更</li>
     * </ul>
     * <p>
     * 容器同类型（O↔O / C↔C）分支对单表三态节点对状态做<b>一次查询</b>：
     * {@link ComparisonContext#enterNodePair} 返回 false 即直接返回——该 false 同时涵盖循环截断
     * （原规则）与「已完成且无变更」的复用命中（不生成路径、不生成变更）；返回 true 时递归子节点，
     * 并在退出时把本次结论（无变更且期间未发生循环截断）写回同一状态。有变化时仍按当前路径输出
     * {@link com.nona.changeTracking.domain.model.changeset.ContainerChangeNode}；节点对状态在正常与
     * 异常退出时均按结论更新，不泄漏到其他路径。
     *
     * @param oldNode     旧节点。
     * @param newNode     新节点。
     * @param context     单次比较上下文。
     * @param accumulator 所属容器的变更收集器。
     */
    private void diffNode(final ValueNode oldNode, final ValueNode newNode,
                          final ComparisonContext context, final ChangeAccumulator accumulator) {
        if (oldNode == newNode) {
            return;
        }

        if (oldNode instanceof NullNode && newNode instanceof NullNode) {
            return;
        }

        if (oldNode instanceof PrimitiveNode oldPrim && newNode instanceof PrimitiveNode newPrim) {
            if (Objects.equals(oldPrim.value(), newPrim.value())) {
                return;
            }
            accumulator.add(new FieldChangeNode(context.currentPath(), oldPrim.value(), newPrim.value()));
            return;
        }

        // 基本值↔基本值（P↔N / N↔P）：快照中业务值可得，仍走 FieldChangeNode
        if ((oldNode instanceof PrimitiveNode || oldNode instanceof NullNode)
                && (newNode instanceof PrimitiveNode || newNode instanceof NullNode)) {
            accumulator.add(new FieldChangeNode(context.currentPath(), extractValue(oldNode), extractValue(newNode)));
            return;
        }

        // 数组↔数组（A↔A）：内容比较（顺序敏感，ArrayNode.equals 已是内容语义）
        if (oldNode instanceof ArrayNode oldArray && newNode instanceof ArrayNode newArray) {
            if (oldArray.equals(newArray)) {
                return;
            }
            accumulator.add(new FieldChangeNode(context.currentPath(), oldArray.array(), newArray.array()));
            return;
        }

        // 容器同类型（O↔O / C↔C）：一次查询同时回答循环终止与安全无变更复用
        final boolean bothObject = oldNode instanceof ObjectNode && newNode instanceof ObjectNode;
        final boolean bothCollection = oldNode instanceof CollectionNode && newNode instanceof CollectionNode;
        if (bothObject || bothCollection) {
            if (!context.enterNodePair(oldNode, newNode)) {
                // false 同时涵盖循环截断（终止递归）与「已完成且无变更」的复用命中（不生成路径、不生成变更）
                return;
            }
            boolean unchanged = false;
            try {
                final ChangeAccumulator inner = new ChangeAccumulator();
                diffChildren(oldNode, newNode, context, inner);
                unchanged = inner.isEmpty();
                if (!unchanged) {
                    accumulator.add(new ContainerChangeNode(context.currentPath(), inner.toList()));
                }
            } finally {
                // 异常退出保持 unchanged=false，置为不可复用
                context.exitNodePair(oldNode, newNode, unchanged);
            }
            return;
        }

        // 容器参与的跨类型变化：快照中无业务对象可提取，原样携带 ValueNode 表示
        accumulator.add(new ObjectFieldChangeNode(context.currentPath(), oldNode, newNode));
    }

    /**
     * 低层方法：负责遍历与收集。
     * <p>
     * 根据节点类型分发到对象或集合子比较，把子变更直接收入所属容器的收集器。
     *
     * @param oldNode     旧节点。
     * @param newNode     新节点。
     * @param context     单次比较上下文。
     * @param accumulator 所属容器的变更收集器。
     */
    private void diffChildren(final ValueNode oldNode, final ValueNode newNode,
                              final ComparisonContext context, final ChangeAccumulator accumulator) {
        if (oldNode instanceof ObjectNode oldObj && newNode instanceof ObjectNode newObj) {
            diffObjectChildren(oldObj, newObj, context, accumulator);
            return;
        }
        if (oldNode instanceof CollectionNode oldColl && newNode instanceof CollectionNode newColl) {
            diffCollectionChildren(oldColl, newColl, context, accumulator);
        }
    }

    /**
     * 比较两个 ObjectNode 的所有字段。
     * <p>
     * 字段变更按<b>声明序</b>输出：先按旧节点的字段声明序比较，再遍历新节点独有字段追加在后；
     * 不建立字段名并集。字段补缺失时视为 {@link NullNode}（与既有语义一致）。
     *
     * @param oldObj      旧对象节点。
     * @param newObj      新对象节点。
     * @param context     单次比较上下文。
     * @param accumulator 所属容器的变更收集器。
     */
    private void diffObjectChildren(final ObjectNode oldObj, final ObjectNode newObj,
                                    final ComparisonContext context, final ChangeAccumulator accumulator) {
        oldObj.forEachField((key, oldFieldNode) -> {
            final ValueNode newFieldNode = fieldOrNullNode(newObj, key);
            context.pushField(key);
            try {
                diffNode(oldFieldNode, newFieldNode, context, accumulator);
            } finally {
                context.pop();
            }
        });
        newObj.forEachField((key, newFieldNode) -> {
            if (oldObj.field(key) != null) {
                return;
            }
            context.pushField(key);
            try {
                diffNode(new NullNode(), newFieldNode, context, accumulator);
            } finally {
                context.pop();
            }
        });
    }

    /**
     * 比较两个 CollectionNode 的所有项。
     * <p>
     * 经 {@link CollectionMatchIndex} 有序匹配标识；每组的共同出现次数按出现次序配对，
     * 再输出新侧多余项、旧侧多余项；出现序后缀由两侧最大出现次数决定。项的路径由上下文按需生成。
     *
     * @param oldColl     旧集合节点。
     * @param newColl     新集合节点。
     * @param context     单次比较上下文。
     * @param accumulator 所属容器的变更收集器。
     */
    private void diffCollectionChildren(final CollectionNode oldColl, final CollectionNode newColl,
                                        final ComparisonContext context, final ChangeAccumulator accumulator) {
        final CollectionMatchIndex index = CollectionMatchIndex.of(oldColl, newColl);
        index.forEachGroup(group -> {
            final boolean useOccurrenceSuffix = group.oldCount() > 1 || group.newCount() > 1;
            final int common = Math.min(group.oldCount(), group.newCount());
            for (int position = 0; position < common; position++) {
                context.pushItem(group.identity(), toOccurrence(useOccurrenceSuffix, position));
                try {
                    diffNode(group.oldItem(position), group.newItem(position), context, accumulator);
                } finally {
                    context.pop();
                }
            }
            for (int position = common; position < group.newCount(); position++) {
                context.pushItem(group.identity(), toOccurrence(useOccurrenceSuffix, position));
                try {
                    accumulator.add(new ItemAddedNode(context.currentPath(), group.newItem(position)));
                } finally {
                    context.pop();
                }
            }
            for (int position = common; position < group.oldCount(); position++) {
                context.pushItem(group.identity(), toOccurrence(useOccurrenceSuffix, position));
                try {
                    accumulator.add(new ItemRemovedNode(context.currentPath(), group.oldItem(position)));
                } finally {
                    context.pop();
                }
            }
        });
    }

    /**
     * 计算出现序后缀值。
     * <p>
     * 仅当同一标识出现多次时才需要后缀；唯一项返回 {@link ComparisonContext#NO_OCCURRENCE}（不加后缀）。
     *
     * @param useOccurrenceSuffix 是否需要后缀。
     * @param zeroBasedIndex      项在该标识分组内的零基索引。
     * @return 从 1 开始的出现序；不需要后缀时返回 {@link ComparisonContext#NO_OCCURRENCE}。
     */
    private static int toOccurrence(final boolean useOccurrenceSuffix, final int zeroBasedIndex) {
        return useOccurrenceSuffix ? zeroBasedIndex + 1 : ComparisonContext.NO_OCCURRENCE;
    }

    /**
     * 按字段名取值，字段缺失时返回 NullNode。
     * <p>
     * {@link ObjectNode#field(String)} 对缺失字段返回 null，而 diff 逻辑需要 NullNode 语义
     * （缺失 = NullNode，与旧 keySet+getOrDefault 行为一致）。
     *
     * @param node 目标 ObjectNode。
     * @param key  字段名。
     * @return 字段的 ValueNode，字段缺失时返回 NullNode。
     */
    private static ValueNode fieldOrNullNode(final ObjectNode node, final String key) {
        final ValueNode value = node.field(key);
        return value != null ? value : new NullNode();
    }

    /**
     * 从基本值节点（PrimitiveNode/NullNode）中提取业务值。
     * <p>
     * 仅用于 {@link #diffNode} 的基本值路径（P↔P / P↔N / N↔P）——业务值可得。
     * 容器节点参与的跨类型变化没有业务值可提取，由
     * {@link com.nona.changeTracking.domain.model.changeset.ObjectFieldChangeNode}
     * 原样携带 ValueNode 节点承载，不经过本方法。
     *
     * @param node 基本值节点（PrimitiveNode 或 NullNode）。
     * @return 业务值：PrimitiveNode 返回其 value，NullNode 返回 null。
     */
    private Object extractValue(final ValueNode node) {
        if (node instanceof PrimitiveNode pn) {
            return pn.value();
        }
        return null;
    }
}
