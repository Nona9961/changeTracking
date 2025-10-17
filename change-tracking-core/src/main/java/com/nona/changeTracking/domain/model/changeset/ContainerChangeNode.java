package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

public record ContainerChangeNode(String path, List<ChangeNode> children) implements ChangeNode {}
