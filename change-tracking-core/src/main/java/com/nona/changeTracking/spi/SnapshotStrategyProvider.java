package com.nona.changeTracking.spi;

/**
 * 服务提供者接口 (SPI)，用于发现和创建 {@link SnapshotStrategy} 实例。
 * <p>
 * 框架使用者可以通过实现此接口来提供自定义的快照策略，并通过 {@link java.util.ServiceLoader}
 * 机制让框架自动发现。
 * <p>
 * 实现类本身必须提供一个 public 的无参构造函数。
 */
public interface SnapshotStrategyProvider {

    /**
     * 返回此快照策略的唯一名称。
     * <p>
     * 这个名称将用于在配置中选择性地激活或引用此策略。
     * 建议使用简短、全小写、用连字符分隔的名称（例如："reflection-based", "kryo-serializer"）。
     *
     * @return 策略的唯一名称，不能为 null 或空白。
     */
    String getName();

    /**
     * 创建一个 {@link SnapshotStrategy} 实例。
     *
     * @param context 一个上下文对象，可用于获取配置或其他依赖项。
     * @return 一个配置好的 {@link SnapshotStrategy} 实例。
     */
    SnapshotStrategy create(CreationContext context);
}
