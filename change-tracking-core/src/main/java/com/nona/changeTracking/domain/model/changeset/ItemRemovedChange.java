package com.nona.changeTracking.domain.model.changeset;

import com.nona.changeTracking.domain.model.snapshot.ValueNode;

/**
 * 表示集合项删除（扁平视图）。
 *
 * @param path                相对路径。
 * @param fullPath            完整路径。
 * @param fieldName           纯字段名（不含索引）。
 * @param collectionFieldName 所属集合字段名，主表字段为 null。
 * @param parentIsCollection  父节点是否为集合。
 * @param removedItem         被删除项的 ValueNode 表示。
 */
public record ItemRemovedChange(
        String path,
        String fullPath,
        String fieldName,
        String collectionFieldName,
        boolean parentIsCollection,
        ValueNode removedItem
) implements Change {

    @Override
    public boolean isParentCollection() {
        return parentIsCollection;
    }
}
