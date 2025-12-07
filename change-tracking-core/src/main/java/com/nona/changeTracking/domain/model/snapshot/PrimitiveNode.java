package com.nona.changeTracking.domain.model.snapshot;

/**
 * 表示基本类型值的快照节点。
 * <p>
 * 包含以下类型的值：
 * <ul>
 *   <li>Java 基本类型及其包装类（int, Integer, boolean, Boolean 等）</li>
 *   <li>String</li>
 *   <li>枚举类型</li>
 *   <li>java.time.* 包中的时间类型</li>
 *   <li>java.math.* 包中的数学类型</li>
 *   <li>UUID</li>
 * </ul>
 *
 * @param value 节点持有的值，可以为 null（但通常使用 {@link NullNode} 表示 null）。
 */
public record PrimitiveNode(Object value) implements ValueNode {
}
