package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.model.snapshot.ArrayNode;
import com.nona.changeTracking.domain.model.snapshot.CollectionNode;
import com.nona.changeTracking.domain.model.snapshot.NullNode;
import com.nona.changeTracking.domain.model.snapshot.ObjectNode;
import com.nona.changeTracking.domain.model.snapshot.PrimitiveNode;
import com.nona.changeTracking.domain.model.snapshot.ValueNode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 快照树（{@link ValueNode}）的深拷贝器（基线导出的内部机制）。
 * <p>
 * 复制语义：
 * <ul>
 *   <li><b>结构复制</b>：结构性节点（{@link com.nona.changeTracking.domain.model.snapshot.ObjectNode}
 *       / {@link com.nona.changeTracking.domain.model.snapshot.CollectionNode} /
 *       {@link com.nona.changeTracking.domain.model.snapshot.ArrayNode}）全部重建为新实例，
 *       输出树与源树零共享结构性节点——跨线程隔离不依赖“构建后不再修改”的不可变心智契约；</li>
 *   <li><b>叶子共享</b>：{@link com.nona.changeTracking.domain.model.snapshot.PrimitiveNode} /
 *       {@link com.nona.changeTracking.domain.model.snapshot.NullNode} 按引用共享——叶子值
 *       不可变，按引用共享天然安全；</li>
 *   <li><b>数组复制</b>：ArrayNode 数组按组件类型复制新数组（一维按组件类型新建 +
 *       浅拷贝，多维逐层递归深拷贝）——数组可变，采用防御拷贝语义；</li>
 *   <li><b>循环/共享结构保持</b>：以 {@link java.util.IdentityHashMap} 缓存已复制的
 *       ObjectNode/CollectionNode，递归进入子节点前先登记空节点（与
 *       {@link ValueNodeSnapshotStrategy} 构建期 visited 缓存同构）——同一源节点复制为
 *       同一新实例，循环引用不栈溢出；</li>
 *   <li><b>identifier 原样携带</b>：ObjectNode 的业务标识符（Long/String/UUID 等
 *       不可变值）按引用共享——复制后的树仍须与重建期新快照的集合项按 identifier
 *       匹配。</li>
 * </ul>
 * <p>
 * 公共门面收敛在 {@code ChangeTracker.captureBaseline()}；本类位于 internal 包——
 * 仅库内部与测试使用，不进入公共 API。
 * <p>
 * 线程安全：无共享可变状态的无状态静态工具，线程安全。
 */
public final class ValueNodeDeepCopier {

    /**
     * 工具类：禁止实例化。
     */
    private ValueNodeDeepCopier() {
        // 工具类：禁止实例化
    }

    /**
     * 深拷贝一棵快照树。
     * <p>
     * 输出与源树<b>结构独立</b>（结构性节点互非同一实例）、内容等价
     * （各节点 {@code equals} 为内容语义，可直接以其断言）；叶子节点按引用共享；
     * 循环/共享结构保持——源树中的同一节点在副本中仍是同一实例。
     *
     * @param root 源树根节点，不能为 null（null 值以 {@link com.nona.changeTracking.domain.model.snapshot.NullNode}
     *             表示，不传 null 引用）。
     * @return 源树的深拷贝。
     * @throws NullPointerException 如果 root 为 null。
     */
    public static ValueNode deepCopy(final ValueNode root) {
        Objects.requireNonNull(root, "Root node cannot be null.");
        return copyRecursive(root, new IdentityHashMap<>());
    }

    /**
     * 递归复制单个节点（sealed {@link ValueNode} 穷举分发）。
     * <p>
     * ObjectNode/CollectionNode 先查缓存（同一源节点 → 同一新实例），未复制过则
     * 先登记空节点再递归填充子节点（先登记后填充，同构建期 visited 流程）；
     * ArrayNode 按组件类型复制新数组；PrimitiveNode/NullNode 值不可变，原实例共享。
     *
     * @param node   源节点，不能为 null。
     * @param copies 已复制节点缓存（源节点 → 新实例，身份语义）。
     * @return 源节点的深拷贝。
     */
    private static ValueNode copyRecursive(final ValueNode node, final IdentityHashMap<ValueNode, ValueNode> copies) {
        if (node instanceof ObjectNode objectNode) {
            return copyObjectNode(objectNode, copies);
        }
        if (node instanceof CollectionNode collectionNode) {
            return copyCollectionNode(collectionNode, copies);
        }
        if (node instanceof ArrayNode arrayNode) {
            return new ArrayNode(deepCopyArray(arrayNode.array()));
        }
        if (node instanceof PrimitiveNode || node instanceof NullNode) {
            // 叶子：值不可变，按引用共享天然安全
            return node;
        }
        // sealed 穷举兜底：ValueNode 新增类型时快速失败，而非静默共享
        throw new IllegalStateException("Unexpected ValueNode type: " + node.getClass().getName());
    }

    /**
     * 复制一个 ObjectNode：新 LinkedHashMap（保持字段声明序）+ identifier 原样共享。
     * <p>
     * 先登记后填充：空节点先行入缓存，循环引用（如 a→b→a）解析到 b 的嵌套 a 时
     * 命中缓存返回同一新实例，不栈溢出。
     *
     * @param source 源节点。
     * @param copies 已复制节点缓存。
     * @return 源节点的深拷贝。
     */
    private static ObjectNode copyObjectNode(final ObjectNode source, final IdentityHashMap<ValueNode, ValueNode> copies) {
        final ValueNode cached = copies.get(source);
        if (cached != null) {
            return (ObjectNode) cached;
        }
        final Map<String, ValueNode> fields = new LinkedHashMap<>();
        final ObjectNode copy = new ObjectNode(fields, source.identifier());
        copies.put(source, copy);
        source.forEachField((name, value) -> fields.put(name, copyRecursive(value, copies)));
        return copy;
    }

    /**
     * 复制一个 CollectionNode：新 ArrayList（保持迭代顺序）。
     * <p>
     * 先登记后填充（同 {@link #copyObjectNode}，循环引用安全）。
     *
     * @param source 源节点。
     * @param copies 已复制节点缓存。
     * @return 源节点的深拷贝。
     */
    private static CollectionNode copyCollectionNode(final CollectionNode source, final IdentityHashMap<ValueNode, ValueNode> copies) {
        final ValueNode cached = copies.get(source);
        if (cached != null) {
            return (CollectionNode) cached;
        }
        final List<ValueNode> items = new ArrayList<>(source.size());
        final CollectionNode copy = new CollectionNode(items);
        copies.put(source, copy);
        source.forEachItem(item -> items.add(copyRecursive(item, copies)));
        return copy;
    }

    /**
     * 深拷贝数组（防御拷贝语义；语义同 {@link ValueNodeSnapshotStrategy} 的数组处理）。
     * <p>
     * 一维：按组件类型创建同类型新数组并浅拷贝（元素为值类型=不可变，浅拷贝安全，
     * 且保持运行时数组类型——消费方强转可用）；多维：逐层递归深拷贝（内层行也是数组）。
     *
     * @param array 源数组。
     * @return 内容相同、互不共享引用的新数组。
     */
    private static Object deepCopyArray(final Object array) {
        final Class<?> componentType = array.getClass().getComponentType();
        final int length = Array.getLength(array);

        if (componentType.isArray()) {
            final Object copy = Array.newInstance(componentType, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, deepCopyArray(Array.get(array, index)));
            }
            return copy;
        }

        final Object copy = Array.newInstance(componentType, length);
        System.arraycopy(array, 0, copy, 0, length);
        return copy;
    }
}