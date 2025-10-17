package com.nona.changeTracking.domain.model.changeset;

import java.util.List;

public record ContainerChange(String path, List<Change> children) implements Change {}
