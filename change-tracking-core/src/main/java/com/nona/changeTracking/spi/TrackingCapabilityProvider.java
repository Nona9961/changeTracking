package com.nona.changeTracking.spi;

import com.nona.changeTracking.domain.capability.TrackingCapability;

import java.util.function.Function;

/**
 * 服务提供者接口 (SPI)，用于发现和创建 {@link TrackingCapability} 实例。
 * <p>
 * 这是框架暴露给外部的、统一的扩展点。第三方开发者可以通过实现此接口，
 * 提供一套完整的、自定义的变更追踪能力（例如，基于JSON序列化、Kryo等）。
 * <p>
 * 实现类必须提供一个 public 的无参构造函数，以便 {@link java.util.ServiceLoader} 能够实例化。
 */
public interface TrackingCapabilityProvider {

    /**
     * 返回此追踪能力的唯一名称。
     * <p>
     * 这个名称将用于在配置中选择性地激活或引用此能力。
     * 建议使用简短、全小写、用连字符分隔的名称（例如："default-reflection", "json-jackson"）。
     *
     * @return 追踪能力的唯一名称，不能为 null 或空白。
     */
    String getName();

    /**
     * 为特定领域类型注册一个非侵入式的业务标识符提取函数。
     * <p>
     * 这个方法允许 Provider 的实现者存储特定于其能力的配置。
     *
     * @param type      领域对象的 Class。
     * @param extractor 一个从领域对象实例中提取唯一标识符的函数。
     * @param <T>       领域对象的类型。
     * @return 当前 Provider 实例，以支持链式调用。
     */
    <T> TrackingCapabilityProvider withIdentifier(Class<T> type, Function<T, Object> extractor);

    /**
     * 创建一个 {@link TrackingCapability} 实例。
     *
     * @return 一个配置好的 {@link TrackingCapability} 实例。
     */
    TrackingCapability<?> create();
}
