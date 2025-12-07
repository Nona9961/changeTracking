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
 * <p>
 * <b>配置方法：</b>
 * <ul>
 *   <li>{@link #withIdentifier(Class, Function)} - 注册业务标识提取器</li>
 *   <li>{@link #withValueType(Class)} - 注册自定义值类型</li>
 *   <li>{@link #withValuePackage(String)} - 注册自定义值类型包</li>
 * </ul>
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
     * 提取器用于在集合项比较时确定两个对象是否代表同一业务实体。
     * 提取的标识符的 {@code hashCode()} 将用于匹配集合中的对应项。
     * <p>
     * 示例：
     * <pre>{@code
     * provider.withIdentifier(Order.class, Order::getId)
     *         .withIdentifier(LineItem.class, LineItem::getSku);
     * }</pre>
     *
     * @param type      领域对象的 Class。
     * @param extractor 一个从领域对象实例中提取唯一标识符的函数。
     * @param <T>       领域对象的类型。
     * @return 当前 Provider 实例，以支持链式调用。
     */
    <T> TrackingCapabilityProvider withIdentifier(Class<T> type, Function<T, Object> extractor);

    /**
     * 注册一个自定义值类型。
     * <p>
     * 该类型会被视为原始值（类似于 String、Integer），不会递归展开其字段。
     * 适用于第三方库中的值对象，如 Joda-Time 的日期类型、Money 类型等。
     * <p>
     * 示例：
     * <pre>{@code
     * provider.withValueType(org.joda.time.DateTime.class)
     *         .withValueType(org.javamoney.moneta.Money.class);
     * }</pre>
     *
     * @param type 要注册为值类型的类，不能为 null。
     * @return 当前 Provider 实例，以支持链式调用。
     */
    TrackingCapabilityProvider withValueType(Class<?> type);

    /**
     * 注册一个自定义值类型包。
     * <p>
     * 该包下的所有类都会被视为原始值，不会递归展开其字段。
     * 适用于整个包都是值对象的情况。
     * <p>
     * 示例：
     * <pre>{@code
     * provider.withValuePackage("org.joda.time")
     *         .withValuePackage("org.javamoney.moneta");
     * }</pre>
     *
     * @param packageName 要注册为值类型包的包名，不能为 null。
     * @return 当前 Provider 实例，以支持链式调用。
     */
    TrackingCapabilityProvider withValuePackage(String packageName);

    /**
     * 创建一个 {@link TrackingCapability} 实例。
     * <p>
     * 创建的实例会包含之前通过 {@code withXxx} 方法配置的所有设置。
     *
     * @return 一个配置好的 {@link TrackingCapability} 实例。
     */
    TrackingCapability<?> create();
}
