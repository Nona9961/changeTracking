package com.nona.changeTracking.internal.capability;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.snapshot.ValueNodeSnapshot;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 基于反射的默认追踪能力提供者。
 * <p>
 * 通过 Java SPI 机制自动发现和加载。
 * 提供者名称为 {@value #NAME}。
 * <p>
 * <b>已知问题：</b>{@link #withIdentifier(Class, Function)} 方法存储了 extractor，
 * 但 {@link #create()} 方法目前未使用它。这是一个待实现的功能。
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
     * 存储类型到标识符提取器的映射。
     * <p>
     * 注意：当前版本中此字段未被使用，是为未来功能预留的。
     */
    private final Map<Class<?>, Function<Object, Object>> identifierExtractors = new HashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>注意：</b>当前实现存储了 extractor，但 {@link #create()} 未使用它。
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> TrackingCapabilityProvider withIdentifier(final Class<T> type, final Function<T, Object> extractor) {
        this.identifierExtractors.put(type, (Function<Object, Object>) extractor);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TrackingCapability<ValueNodeSnapshot> create() {
        return new DefaultTrackingCapability();
    }
}
