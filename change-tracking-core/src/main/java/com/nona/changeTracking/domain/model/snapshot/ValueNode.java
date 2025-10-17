package com.nona.changeTracking.domain.model.snapshot;

public sealed interface ValueNode permits PrimitiveNode, ObjectNode, CollectionNode, NullNode {
    // 标记接口
}
