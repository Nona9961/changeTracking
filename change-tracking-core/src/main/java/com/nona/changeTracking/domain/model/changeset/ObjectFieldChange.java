package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示对象/集合字段整体替换（扁平视图）。
 * <p>
 * 与 {@link ValueChange} 的分界（dispatch 表）：字段两侧节点类型不同且至少一侧是
 * 容器/数组节点（ObjectNode/CollectionNode/ArrayNode）时，快照中<b>没有业务对象可提取</b>
 * （快照只持有 ValueNode 表示，不持业务对象引用），本类型原样携带两侧 ValueNode 节点，
 * 由消费方按类型解读（NullNode=清空、ObjectNode=赋值、PrimitiveNode=值、ArrayNode=数组）。
 * 基本值之间的变化（业务值可得）由 {@link ValueChange} 承载。
 * <p>
 * 此变更在 {@link ChangeSet#getAllChanges()} 与 {@link ChangeSet#getLeafChanges()}
 * 中均为叶子变更（不递归子节点）。
 *
 * @param path                相对路径。
 * @param fullPath            完整路径。
 * @param fieldName           纯字段名（不含索引）。
 * @param collectionFieldName 所属集合字段名，主表字段为 null。
 * @param parentIsCollection  父节点是否为集合。
 * @param oldNode             变更前的 ValueNode 表示。
 * @param newNode             变更后的 ValueNode 表示。
 */
public record ObjectFieldChange(
        String path,
        String fullPath,
        String fieldName,
        String collectionFieldName,
        boolean parentIsCollection,
        ValueNode oldNode,
        ValueNode newNode
) implements Change {

    @Override
    public boolean isParentCollection() {
        return parentIsCollection;
    }
}
