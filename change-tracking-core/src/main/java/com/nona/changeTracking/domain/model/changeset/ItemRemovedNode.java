package com.nona.changeTracking.domain.model.changeset;

public record ItemRemovedNode(String path, Object removedItem) implements ChangeNode {}
