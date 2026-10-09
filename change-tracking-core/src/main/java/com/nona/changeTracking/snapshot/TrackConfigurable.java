package com.nona.changeTracking.snapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 快照侧只读配置视图：声明快照构建与集合项匹配所需的配置，由追踪能力装配方实现。
 * <p>
 * 本接口是同一限界上下文内的依赖倒置端口——由消费方（快照侧）声明、由提供方
 * （追踪装配侧）实现，快照策略据此读取只读配置：
 * <ul>
 *   <li><b>标识符提取器</b> - 用于集合项匹配的业务标识提取函数</li>
 *   <li><b>自定义值类型</b> - 被视为原始值的额外类型</li>
 *   <li><b>自定义值类型包</b> - 被视为原始值的额外包名</li>
 * </ul>
 * <p>
 * 所有方法都提供了返回空集合的默认实现，允许实现类只覆盖需要的方法。
 *
 * @see com.nona.changeTracking.tracking.TrackingConfiguration 默认实现
 */
public interface TrackConfigurable {

    /**
     * 获取标识符提取器映射。
     * <p>
     * 键为领域对象类型，值为从该类型实例中提取业务标识的函数。
     * 提取的标识符的 {@code hashCode()} 将用于集合项匹配。
     *
     * @return 不可变的标识符提取器映射，默认返回空映射。
     */
    default Map<Class<?>, Function<Object, Object>> getIdentifierExtractors() {
        return Collections.emptyMap();
    }

    /**
     * 获取自定义值类型集合。
     * <p>
     * 这些类型会被视为原始值，不会递归展开其字段。
     * 适用于第三方库中的值对象（如 Joda-Time、Money 等）。
     *
     * @return 不可变的自定义值类型集合，默认返回空集合。
     */
    default Set<Class<?>> getCustomValueTypes() {
        return Collections.emptySet();
    }

    /**
     * 获取自定义值类型包名集合。
     * <p>
     * 这些包下的所有类都会被视为原始值。
     * 适用于整个包都是值对象的情况（如 org.joda.time）。
     *
     * @return 不可变的自定义值类型包名集合，默认返回空集合。
     */
    default Set<String> getCustomValuePackages() {
        return Collections.emptySet();
    }
}
