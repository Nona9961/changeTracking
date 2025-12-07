package com.nona.changeTracking.domain.model.snapshot;

/**
 * 表示 null 值的快照节点。
 * <p>
 * 这是一个单例模式的值对象，所有 null 值都用此类型表示。
 * 使用专门的类型而非 null 引用，可以在模式匹配中更清晰地处理 null 情况。
 */
public record NullNode() implements ValueNode {
}
