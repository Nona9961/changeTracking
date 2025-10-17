package com.nona.changeTracking.domain.model.snapshot;

import java.util.Collection;

public record CollectionNode(Collection<ValueNode> items) implements ValueNode {}
