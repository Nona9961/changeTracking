package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.spi.CreationContext;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategyProvider;

public class ReflectionSnapshotStrategyProvider implements SnapshotStrategyProvider {

    public static final String NAME = "reflection";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public SnapshotStrategy create(CreationContext context) {
        return new ReflectionMapSnapshotStrategy();
    }
}
