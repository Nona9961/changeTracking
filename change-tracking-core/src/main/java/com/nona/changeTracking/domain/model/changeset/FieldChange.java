package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示字段值变更（扁平视图）。
 *
 * @param path                相对路径。
 * @param fullPath            完整路径。
 * @param fieldName           纯字段名（不含索引）。
 * @param collectionFieldName 所属集合字段名，主表字段为 null。
 * @param parentIsCollection  父节点是否为集合。
 * @param oldValue            变更前的值。
 * @param newValue            变更后的值。
 */
public record FieldChange(
        String path,
        String fullPath,
        String fieldName,
        String collectionFieldName,
        boolean parentIsCollection,
        Object oldValue,
        Object newValue
) implements Change {

    @Override
    public boolean isParentCollection() {
        return parentIsCollection;
    }
}
