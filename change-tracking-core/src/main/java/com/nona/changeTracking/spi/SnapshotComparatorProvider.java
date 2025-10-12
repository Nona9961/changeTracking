package com.nona.changeTracking.spi;

import com.nona.changeTracking.domain.detector.SnapshotComparator;

/**
 * 服务提供者接口 (SPI)，用于发现和创建 {@link SnapshotComparator} 实例。
 * <p>
 * 这是框架推荐的扩展方式。实现此接口，而不是直接实现 {@code SnapshotComparator}，
 * 可以让实现者自由定义其构造函数以接收依赖项，而不是被 {@link java.util.ServiceLoader}
 * 的“必须有无参构造函数”规则所限制。
 * <p>
 * 实现类本身必须提供一个 public 的无参构造函数。
 */
public interface SnapshotComparatorProvider {

    /**
     * 创建一个 {@link SnapshotComparator} 实例。
     *
     * @param context 一个上下文对象，可用于获取配置或其他依赖项。
     * @return 一个配置好的 {@link SnapshotComparator} 实例。
     */
    SnapshotComparator<?> create(CreationContext context);
}
