package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.spi.CreationContext;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 变更追踪的配置类，封装所有可定制的追踪行为。
 * <p>
 * 此类是不可变的，实现 {@link CreationContext} 接口。
 * <p>
 * 配置项包括：
 * <ul>
 *   <li><b>标识符提取器</b> - 用于集合项匹配的业务标识提取函数</li>
 *   <li><b>自定义值类型</b> - 被视为原始值的额外类型</li>
 *   <li><b>自定义值类型包</b> - 被视为原始值的额外包名</li>
 * </ul>
 *
 * @see TrackingCapability 使用此配置的能力接口
 * @see CreationContext SPI 创建上下文接口
 */
@Getter
public final class TrackingConfiguration implements CreationContext {

    /**
     * 空配置单例。
     */
    private static final TrackingConfiguration EMPTY = new TrackingConfiguration(
            Collections.emptyMap(),
            Collections.emptySet(),
            Collections.emptySet()
    );

    /**
     * 标识符提取器映射。
     * 键为领域对象类型，值为从该类型实例中提取业务标识的函数。
     */
    private final Map<Class<?>, Function<Object, Object>> identifierExtractors;

    /**
     * 自定义值类型集合。
     * 这些类型会被视为原始值，不会递归展开其字段。
     */
    private final Set<Class<?>> customValueTypes;

    /**
     * 自定义值类型包名集合。
     * 这些包下的所有类都会被视为原始值。
     */
    private final Set<String> customValuePackages;

    /**
     * 创建配置实例。
     *
     * @param identifierExtractors 标识符提取器映射
     * @param customValueTypes     自定义值类型集合
     * @param customValuePackages  自定义值类型包名集合
     */
    public TrackingConfiguration(
            final Map<Class<?>, Function<Object, Object>> identifierExtractors,
            final Set<Class<?>> customValueTypes,
            final Set<String> customValuePackages) {
        this.identifierExtractors = identifierExtractors;
        this.customValueTypes = customValueTypes;
        this.customValuePackages = customValuePackages;
    }

    /**
     * 创建一个空配置（无自定义配置）。
     *
     * @return 空配置实例。
     */
    public static TrackingConfiguration empty() {
        return EMPTY;
    }
}

