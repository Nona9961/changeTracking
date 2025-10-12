package com.nona.changeTracking.domain.detector;

import com.nona.changeTracking.domain.model.unitofwork.Snapshot;
import com.nona.changeTracking.spi.CreationContext;
import com.nona.changeTracking.spi.SnapshotComparatorProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

public final class ChangeDetectorBuilder {

    private final Map<Class<? extends Snapshot<?>>, SnapshotComparator<?>> comparators = new HashMap<>();

    private ChangeDetectorBuilder() {}

    public static ChangeDetectorBuilder create() {
        return new ChangeDetectorBuilder();
    }

    public ChangeDetectorBuilder withDefaults() {
        // 1. 创建一个空的上下文实例
        final CreationContext context = new CreationContext() {};

        // 2. 使用 stream() API 安全地加载和处理 Provider
        ServiceLoader.load(SnapshotComparatorProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get) // 获取 Provider 实例
                .map(provider -> provider.create(context)) // 调用 create() 获取 Comparator 实例
                .forEach(comparator -> this.comparators.put(comparator.getSupportedSnapshotType(), comparator));

        return this;
    }

    public <S extends Snapshot<?>> ChangeDetectorBuilder withComparator(
            final Class<S> type,
            final SnapshotComparator<S> comparator) {
        this.comparators.put(type, comparator);
        return this;
    }

    public ChangeDetector build() {
        return new ChangeDetector(this.comparators);
    }
}
