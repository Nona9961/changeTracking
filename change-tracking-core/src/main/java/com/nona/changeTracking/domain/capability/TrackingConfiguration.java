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
 * 此类是<b>真正不可变</b>的，实现 {@link CreationContext} 接口：
 * <ul>
 *   <li><b>构造时防御拷贝</b> - 构造器通过 {@link Map#copyOf} / {@link Set#copyOf}
 *       拷贝调用方传入的集合，构造后外部对原集合的修改不影响配置内部状态</li>
 *   <li><b>getter 返回不可变集合</b> - 内部存储即为不可变副本，任何 add/put 抛
 *       {@link UnsupportedOperationException}</li>
 * </ul>
 * <p>
 * 配置项包括：
 * <ul>
 *   <li><b>标识符提取器</b> - 用于集合项匹配的业务标识提取函数</li>
 *   <li><b>自定义值类型</b> - 被视为原始值的额外类型</li>
 *   <li><b>自定义值类型包</b> - 被视为原始值的额外包名</li>
 * </ul>
 * <p>
 * 注意：拷贝语义要求传入集合不含 null 元素/key（{@code Map.copyOf}/{@code Set.copyOf} 约束）。
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
     * <p>
     * 构造器对传入集合做防御拷贝（{@code Map.copyOf} / {@code Set.copyOf}）：
     * 外部可变集合在构造后被切断引用，后续外部修改不影响配置内部状态；
     * 对已不可变的输入返回原实例，零额外开销。
     *
     * @param identifierExtractors 标识符提取器映射
     * @param customValueTypes     自定义值类型集合
     * @param customValuePackages  自定义值类型包名集合
     */
    public TrackingConfiguration(
            final Map<Class<?>, Function<Object, Object>> identifierExtractors,
            final Set<Class<?>> customValueTypes,
            final Set<String> customValuePackages) {
        this.identifierExtractors = Map.copyOf(identifierExtractors);
        this.customValueTypes = Set.copyOf(customValueTypes);
        this.customValuePackages = Set.copyOf(customValuePackages);
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

