package com.nona.changeTracking.domain.model.changeset;

public sealed interface Change permits FieldChange, ContainerChange, ItemAddedChange, ItemRemovedChange {
    String path();
}
