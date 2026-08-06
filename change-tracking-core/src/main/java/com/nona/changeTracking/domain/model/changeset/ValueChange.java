package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示基本值字段的变更（扁平视图）。
 * <p>
 * 与 {@link ObjectFieldChange} 的分界（dispatch 表）：
 * <ul>
 *   <li>本类型覆盖<b>基本值之间</b>的变化：{@code PrimitiveNode↔PrimitiveNode}、
 *       {@code PrimitiveNode↔NullNode}、{@code NullNode↔PrimitiveNode}——此时快照中
 *       可提取业务值，{@code oldValue()}/{@code newValue()} 是<b>业务值</b>
 *       （如 {@code "Alice"}、{@code null}、{@code 30}），消费方可安全强转业务类型</li>
 *   <li>容器/数组节点（ObjectNode/CollectionNode/ArrayNode）参与的跨类型变化没有业务值可提取
 *       （快照只持有 ValueNode 表示，不持业务对象引用），由 {@link ObjectFieldChange}
 *       原样携带 ValueNode 节点承载</li>
 * </ul>
 *
 * @param path                相对路径。
 * @param fullPath            完整路径。
 * @param fieldName           纯字段名（不含索引）。
 * @param collectionFieldName 所属集合字段名，主表字段为 null。
 * @param parentIsCollection  父节点是否为集合。
 * @param oldValue            变更前的业务值。
 * @param newValue            变更后的业务值。
 */
public record ValueChange(
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
