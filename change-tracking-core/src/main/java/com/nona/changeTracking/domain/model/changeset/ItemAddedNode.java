package com.nona.changeTracking.domain.model.changeset;

public record ItemAddedNode(String path, Object addedItem) implements ChangeNode {}
