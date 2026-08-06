package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示对象/集合字段整体替换的节点（树形视图）。
 * <p>
 * 叶子节点：字段两侧的节点类型不同且至少一侧是容器/数组节点
 * （ObjectNode/CollectionNode/ArrayNode）时（如 ObjectNode↔NullNode、
 * ObjectNode↔PrimitiveNode、CollectionNode↔ObjectNode、CollectionNode↔NullNode、
 * ArrayNode↔NullNode 等），快照中<b>没有业务对象可提取</b>，只有 ValueNode 表示——
 * 本节点原样携带两侧节点，由消费方按类型解读（NullNode=清空、ObjectNode=赋值、
 * PrimitiveNode=值、ArrayNode=数组），无需下钻子节点。
 * <p>
 * 与 {@link FieldChangeNode} 的分界：基本值之间的变化
 * （PrimitiveNode/NullNode 组合，业务值可得）走 FieldChangeNode；
 * 容器/数组节点参与的跨类型变化走本类型。
 *
 * @param path    字段的路径。
 * @param oldNode 变更前的 ValueNode 表示。
 * @param newNode 变更后的 ValueNode 表示。
 */
public record ObjectFieldChangeNode(String path, ValueNode oldNode, ValueNode newNode) implements ChangeNode {
}
