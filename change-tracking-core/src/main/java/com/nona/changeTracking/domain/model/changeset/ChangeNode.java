package com.nona.changeTracking.domain.model.changeset;

public sealed interface ChangeNode permits FieldChangeNode, ContainerChangeNode, ItemAddedNode, ItemRemovedNode {
    String path();
}
