package com.nona.changeTracking.domain.model.changeset;

public record ItemRemovedChange(String path, Object removedItem) implements Change {}
