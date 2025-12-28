package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

/**
 * 表示容器变更（扁平视图）。
 * <p>
 * 仅在 {@link ChangeSet#getAllChanges()} 中出现。
 *
 * @param path                相对路径。
 * @param fullPath            完整路径。
 * @param fieldName           纯字段名（不含索引）。
 * @param collectionFieldName 所属集合字段名，主表字段为 null。
 * @param parentIsCollection  父节点是否为集合。
 * @param children            子变更列表。
 */
public record ContainerChange(
        String path,
        String fullPath,
        String fieldName,
        String collectionFieldName,
        boolean parentIsCollection,
        List<Change> children
) implements Change {

    @Override
    public boolean isParentCollection() {
        return parentIsCollection;
    }
}
