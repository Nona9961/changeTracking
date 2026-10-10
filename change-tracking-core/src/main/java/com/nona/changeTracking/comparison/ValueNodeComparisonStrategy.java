package com.nona.changeTracking.comparison;

import com.nona.changeTracking.change.Change;
import com.nona.changeTracking.change.ContainerChange;
import com.nona.changeTracking.change.ItemAddedChange;
import com.nona.changeTracking.change.ItemRemovedChange;
import com.nona.changeTracking.change.ObjectFieldChange;
import com.nona.changeTracking.change.ValueChange;
import com.nona.changeTracking.snapshot.ArrayNode;
import com.nona.changeTracking.snapshot.CollectionNode;
import com.nona.changeTracking.snapshot.NullNode;
import com.nona.changeTracking.snapshot.ObjectNode;
import com.nona.changeTracking.snapshot.PrimitiveNode;
import com.nona.changeTracking.snapshot.ValueNode;
import com.nona.changeTracking.snapshot.ValueNodeSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * 默认比较策略：递归比较两个 {@link ValueNodeSnapshot} 的树结构，直接产出统一变更结果。
 * <p>
 * 比较算法采用双层递归设计（{@code diffNode} 负责分发与变更分类，{@code diffChildren} 负责遍历与收集），
 * 并在一次 {@code compare} 内创建独立的 {@link ComparisonContext} 与 {@link ChangeAccumulator}：
 * 路径与定位由上下文承载（字段段与集合项段按需形成定位），对象字段不建立字段名并集，集合项经
 * {@link CollectionMatchIndex} 有序匹配，子变更按发现顺序收入收集器，零或多项结果直接交给所属分组。
 * <p>
 * 结果契约与 {@link ComparisonStrategy#compare} 一致：目标根下的只读变更列表，无变化返回空列表，
 * 真实根值变化为空路径原子变化，不产出包装根。既有匹配规则、输出顺序、变更类型、载荷与循环终止
 * 语义保持不变。
 * <p>
 * 分发与遍历处的 {@code push / try / finally pop} 作用域样板有意保留在各调用点，不提取为统一的
 * 「带作用域分发」方法：统一入口只能把被执行的比较动作作为 lambda 或回调传入，而 lambda 的捕获会在
 * 比较热路径上为每次分发额外分配，该路径的每操作分配量正是比较策略的性能判据指标；显式成对写法同时
 * 让「进入上下文必须退出」的资源纪律在调用处可见。
 */
public class ValueNodeComparisonStrategy implements ComparisonStrategy<ValueNodeSnapshot> {

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
     * 容器节点参与的跨类型变化没有业务值可提取，由 {@link ObjectFieldChange}
     * 原样携带 ValueNode 节点承载，不经过本方法。
     *
     * @param node 基本值节点（PrimitiveNode 或 NullNode）。
     * @return 业务值：PrimitiveNode 返回其 value，NullNode 返回 null。
     */
    private static Object extractValue(final ValueNode node) {
        if (node instanceof PrimitiveNode pn) {
            return pn.value();
        }
        return null;
    }

    /**
     * 判断节点对是否为容器同类型（O↔O / C↔C）。
     * <p>
     * 根层分发与嵌套分发共用本判定，使「容器对」在根处展开子节点、在嵌套处经节点对状态进入递归的
     * 识别保持一致。
     *
     * @param oldNode 旧节点。
     * @param newNode 新节点。
     * @return 两侧同为 ObjectNode 或同为 CollectionNode 时返回 true。
     */
    private static boolean isContainerPair(final ValueNode oldNode, final ValueNode newNode) {
        return (oldNode instanceof ObjectNode && newNode instanceof ObjectNode)
                || (oldNode instanceof CollectionNode && newNode instanceof CollectionNode);
    }

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
     * 产出目标根下的变更结果列表：根节点对为容器（O↔O / C↔C）时展开子节点，其他组合按完整 dispatch
     * 表处理（基本值之间的变化、数组值变化、容器同类型递归与容器参与的跨类型替换）。
     */
    @Override
    public List<Change> compare(final ValueNodeSnapshot oldSnapshot, final ValueNodeSnapshot newSnapshot) {
        Objects.requireNonNull(oldSnapshot, "oldSnapshot");
        Objects.requireNonNull(newSnapshot, "newSnapshot");
        final ComparisonContext context = new ComparisonContext();
        final ChangeAccumulator accumulator = new ChangeAccumulator();
        diffRoot(oldSnapshot.getSnapshotData(), newSnapshot.getSnapshotData(), context, accumulator);
        return List.copyOf(accumulator.toList());
    }

    /**
     * 根节点分发：容器对（O↔O / C↔C）展开子节点，其他组合走 {@link #diffNode} 叶子级 dispatch。
     * <p>
     * 根节点可能是任意 {@link ValueNode}（如快照根直接是数组/基本值），
     * 与嵌套节点一样需要完整的 dispatch 表（A↔A 内容比较、跨类型 ObjectFieldChange 等）。
     * 容器对在根处直接展开：根下的结果列表就是统一结果本身，不产生包装根。
     *
     * @param oldNode     旧根节点。
     * @param newNode     新根节点。
     * @param context     单次比较上下文（路径段栈与节点对状态表）。
     * @param accumulator 根层变更收集器。
     */
    private void diffRoot(final ValueNode oldNode, final ValueNode newNode,
                          final ComparisonContext context, final ChangeAccumulator accumulator) {
        if (isContainerPair(oldNode, newNode)) {
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
     *   <li>P↔P / P↔N / N↔P（基本值之间）→ {@link ValueChange}（业务值可得）</li>
     *   <li>A↔A（数组之间）→ 内容相等=无变更（{@link com.nona.changeTracking.snapshot.ArrayNode#equals} 已是内容语义）；
     *       不等（含顺序变）→ {@link ValueChange}（载荷为数组实例，消费方可强转）</li>
     *   <li>O↔O / C↔C（容器同类型）→ 递归子节点，有变更则包裹在 {@link ContainerChange} 中</li>
     *   <li>其余组合（容器/数组参与的跨类型变化）→ {@link ObjectFieldChange}（原样携带 ValueNode）</li>
     *   <li>N↔N / 同实例 → 无变更</li>
     * </ul>
     * <p>
     * 容器同类型（O↔O / C↔C）分支对单表三态节点对状态做<b>一次查询</b>：
     * {@link ComparisonContext#enterNodePair} 返回 false 即直接返回——该 false 同时涵盖循环截断
     * （原规则）与「已完成且无变更」的复用命中（不生成路径、不生成变更）；返回 true 时递归子节点，
     * 并在退出时把本次结论（无变更且期间未发生循环截断）写回同一状态。有变化时仍按当前路径输出
     * {@link ContainerChange}；节点对状态在正常与异常退出时均按结论更新，不泄漏到其他路径。
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
            accumulator.add(new ValueChange(context.currentLocation(), oldPrim.value(), newPrim.value()));
            return;
        }

        // 基本值↔基本值（P↔N / N↔P）：快照中业务值可得，仍走 ValueChange
        if ((oldNode instanceof PrimitiveNode || oldNode instanceof NullNode)
                && (newNode instanceof PrimitiveNode || newNode instanceof NullNode)) {
            accumulator.add(new ValueChange(context.currentLocation(), extractValue(oldNode), extractValue(newNode)));
            return;
        }

        // 数组↔数组（A↔A）：内容比较（顺序敏感，ArrayNode.equals 已是内容语义）
        if (oldNode instanceof ArrayNode oldArray && newNode instanceof ArrayNode newArray) {
            if (oldArray.equals(newArray)) {
                return;
            }
            accumulator.add(new ValueChange(context.currentLocation(), oldArray.array(), newArray.array()));
            return;
        }

        // 容器同类型（O↔O / C↔C）：一次查询同时回答循环终止与安全无变更复用
        if (isContainerPair(oldNode, newNode)) {
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
                    accumulator.add(new ContainerChange(context.currentLocation(), inner.toList()));
                }
            } finally {
                // 异常退出保持 unchanged=false，置为不可复用
                context.exitNodePair(oldNode, newNode, unchanged);
            }
            return;
        }

        // 容器参与的跨类型变化：快照中无业务对象可提取，原样携带 ValueNode 表示
        accumulator.add(new ObjectFieldChange(context.currentLocation(), oldNode, newNode));
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
     * 再输出新侧多余项（{@link ItemAddedChange}）、旧侧多余项（{@link ItemRemovedChange}）；
     * 出现序后缀由两侧最大出现次数决定。项的定位由上下文按需形成。
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
                    accumulator.add(new ItemAddedChange(context.currentLocation(), group.newItem(position)));
                } finally {
                    context.pop();
                }
            }
            for (int position = common; position < group.oldCount(); position++) {
                context.pushItem(group.identity(), toOccurrence(useOccurrenceSuffix, position));
                try {
                    accumulator.add(new ItemRemovedChange(context.currentLocation(), group.oldItem(position)));
                } finally {
                    context.pop();
                }
            }
        });
    }
}
