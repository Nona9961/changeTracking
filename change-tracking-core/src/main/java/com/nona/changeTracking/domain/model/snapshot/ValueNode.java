package com.nona.changeTracking.domain.model.snapshot;

/**
 * 快照数据的树形结构节点的密封接口。
 * <p>
 * 这是一个标记接口，定义了快照数据的所有可能节点类型。
 * 使用 Java 21 的 sealed interface 特性，确保类型安全和穷尽的模式匹配。
 * <p>
 * 允许的实现类型：
 * <ul>
 *   <li>{@link PrimitiveNode} - 基本类型值（包括 String、枚举、java.time.* 等）</li>
 *   <li>{@link ObjectNode} - 复杂对象（包含字段映射）</li>
 *   <li>{@link CollectionNode} - 集合类型</li>
 *   <li>{@link ArrayNode} - 数组值（内容语义，顺序敏感）</li>
 *   <li>{@link NullNode} - null 值</li>
 * </ul>
 */
public sealed interface ValueNode permits PrimitiveNode, ObjectNode, CollectionNode, ArrayNode, NullNode {
}
