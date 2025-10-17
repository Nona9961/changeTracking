package com.nona.changeTracking.domain.model.changeset;

public record FieldChange(String path, Object oldValue, Object newValue) implements Change {}
