package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;

import java.util.*;
import java.util.function.Function;

/**
 * 基于反射的默认追踪能力提供者。
 * <p>
 * 通过 Java SPI 机制自动发现和加载。
 * 提供者名称为 {@value #NAME}。
 * <p>
 * 支持的配置：
 * <ul>
 *   <li>{@link #withIdentifier(Class, Function)} - 注册业务标识提取器</li>
 *   <li>{@link #withValueType(Class)} - 注册自定义值类型</li>
 *   <li>{@link #withValuePackage(String)} - 注册自定义值类型包</li>
 * </ul>
 *
 * @see TrackingCapabilityProvider SPI 接口
 * @see DefaultTrackingCapability 创建的能力实现
 */
public class DefaultTrackingCapabilityProvider implements TrackingCapabilityProvider {

    /**
     * 此提供者的名称标识符。
     */
    public static final String NAME = "default-reflection";

    /**
     * 标识符提取器映射。
     */
    private final Map<Class<?>, Function<Object, Object>> identifierExtractors = new HashMap<>();

    /**
     * 自定义值类型集合。
     */
    private final Set<Class<?>> customValueTypes = new HashSet<>();

    /**
     * 自定义值类型包名集合。
     */
    private final Set<String> customValuePackages = new HashSet<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> TrackingCapabilityProvider withIdentifier(final Class<T> type, final Function<T, Object> extractor) {
        Objects.requireNonNull(type, "Type cannot be null.");
        Objects.requireNonNull(extractor, "Extractor cannot be null.");
        this.identifierExtractors.put(type, (Function<Object, Object>) extractor);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TrackingCapabilityProvider withValueType(final Class<?> type) {
        Objects.requireNonNull(type, "Type cannot be null.");
        this.customValueTypes.add(type);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TrackingCapabilityProvider withValuePackage(final String packageName) {
        Objects.requireNonNull(packageName, "Package name cannot be null.");
        this.customValuePackages.add(packageName);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TrackingCapability<ValueNodeSnapshot> create() {
        final TrackingConfiguration configuration = new TrackingConfiguration(
                Map.copyOf(identifierExtractors),
                Set.copyOf(customValueTypes),
                Set.copyOf(customValuePackages)
        );
        return new DefaultTrackingCapability(configuration);
    }
}
