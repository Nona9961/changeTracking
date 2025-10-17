package com.nona.changeTracking.domain.model.snapshot;

import java.util.Map;

public record ObjectNode(Map<String, ValueNode> fields, int identityHashCode) implements ValueNode {
    public ObjectNode(Map<String, ValueNode> fields) {
        this(fields, 0); // 默认为0，表示非集合项
    }
}
