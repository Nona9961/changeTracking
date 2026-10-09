package com.nona.changeTracking.change;

import com.nona.changeTracking.snapshot.ValueNode;

/**
 * 表示对象/集合字段的整体替换：一次原子变化，原样携带两侧快照节点。
 * <p>
 * 与 {@link ValueChange} 的分界（dispatch 表）：字段两侧节点类型不同且至少一侧是
 * 容器/数组节点（ObjectNode/CollectionNode/ArrayNode）时，快照中<b>没有业务对象可提取</b>
 * （快照只持有 ValueNode 表示，不持业务对象引用），本类型原样携带两侧 ValueNode 节点，
 * 由消费方按类型解读（NullNode=清空、ObjectNode=赋值、PrimitiveNode=值、ArrayNode=数组）。
 * 基本值之间的变化（业务值可得）由 {@link ValueChange} 承载。
 * <p>
 * 本类型是叶子：整体替换不递归展开为载荷内部的重复操作。
 *
 * @param location 变更定位
 * @param oldNode  变更前的 ValueNode 表示
 * @param newNode  变更后的 ValueNode 表示
 */
public record ObjectFieldChange(ChangeLocation location, ValueNode oldNode, ValueNode newNode) implements Change {
}
