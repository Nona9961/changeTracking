package com.nona.changeTracking.domain.model.changeset;

/**
 * 表示基本值字段的变更：一次原子变化，载荷是可直接消费的业务值。
 * <p>
 * 与 {@link ObjectFieldChange} 的分界（dispatch 表）：
 * <ul>
 *   <li>本类型覆盖<b>基本值之间</b>的变化：{@code PrimitiveNode↔PrimitiveNode}、
 *       {@code PrimitiveNode↔NullNode}、{@code NullNode↔PrimitiveNode}，以及数组值之间的变化
 *       （载荷为数组实例）——此时快照中可提取业务值，{@code oldValue()}/{@code newValue()}
 *       是<b>业务值</b>（如 {@code "Alice"}、{@code null}、{@code 30}）</li>
 *   <li>容器/数组节点（ObjectNode/CollectionNode/ArrayNode）参与的跨类型变化没有业务值可提取
 *       （快照只持有 ValueNode 表示，不持业务对象引用），由 {@link ObjectFieldChange}
 *       原样携带 ValueNode 节点承载</li>
 * </ul>
 * <p>
 * 本类型是叶子：载荷是业务值，不参与节点遍历；整体替换不递归展开为重复操作。
 *
 * @param location 变更定位
 * @param oldValue 变更前的业务值
 * @param newValue 变更后的业务值
 */
public record ValueChange(ChangeLocation location, Object oldValue, Object newValue) implements Change {
}
