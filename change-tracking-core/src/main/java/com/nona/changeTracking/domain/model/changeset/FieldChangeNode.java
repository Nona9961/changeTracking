package com.nona.changeTracking.domain.model.changeset;

public record FieldChangeNode(String path, Object oldValue, Object newValue) implements ChangeNode {}
