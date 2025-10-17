package com.nona.changeTracking.domain.model.changeset;

public record ItemAddedChange(String path, Object addedItem) implements Change {}
