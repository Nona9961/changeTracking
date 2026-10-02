package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import com.nona.changeTracking.internal.util.ReflectionUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Currency;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 配置绑定的类型规则缓存（ADR-002）：按快照策略实例隔离，复用值类型分类、值数组分类与标识规则解析结果。
 * <p>
 * <b>与 {@link ReflectionMetadataCache} 的分界</b>：本缓存只保存与
 * {@link TrackingConfiguration} 相关、且与具体业务实例无关的规则解析结果；不保存字段结构、
 * 业务实例、字段值或函数执行结果，不进入共享反射元数据，也不建立全局配置注册表。不同配置各持有
 * 自己的缓存实例，规则不跨配置复用；同一 capability 的重复使用复用本缓存。
 * <p>
 * <b>按需解析</b>：三个方面分别由独立的实例级 {@link ClassValue} 承载——{@link #isValueType(Class)}、
 * {@link #isValueArray(Class)} 与 {@link #identifierRule(Class)} 各自首次调用时解析自身分支，互不触发
 * 未走到的解析；不以长期 {@code Map<Class, ?>} 留住所有遇到过的类型。
 * <p>
 * <b>未找到也缓存</b>：标识提取规则未找到时缓存显式的 identity 回退规则
 * {@link IdentifierRule#IDENTITY_FALLBACK}，不用 null 同时表示“未缓存”与“未找到”；已注册提取器
 * 每次仍由快照策略实际执行，执行结果不缓存。
 * <p>
 * {@link ClassValue} 条目挂在对应 {@link Class} 上，缓存项不反向捕获快照策略或缓存所有者。
 */
final class ConfiguredTypeRulesCache {

    /**
     * 默认的值类型包名：这些包下的类按值类型处理。
     */
    private static final Set<String> DEFAULT_VALUE_PACKAGES = Set.of(
            "java.time",
            "java.math",
            "java.net");

    /**
     * 默认的值类型类：这些类按值类型处理。
     */
    private static final Set<Class<?>> DEFAULT_VALUE_CLASSES = Set.of(
            UUID.class,
            Locale.class,
            Currency.class,
            Pattern.class,
            File.class,
            Path.class);

    /** 值类型分类缓存：按类按需解析并复用。 */
    private final ClassValue<Boolean> valueTypes;

    /** 值数组分类缓存：按类按需解析并复用。 */
    private final ClassValue<Boolean> valueArrays;

    /** 标识规则缓存：按类按需解析并复用，含未找到的显式回退规则。 */
    private final ClassValue<IdentifierRule> identifierRules;

    /**
     * 绑定不可变配置创建规则缓存。
     *
     * @param configuration 快照策略持有的不可变追踪配置，不能为 null
     * @throws NullPointerException 如果 configuration 为 null
     */
    ConfiguredTypeRulesCache(final TrackingConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        this.valueTypes = new ValueTypeLookup(configuration);
        this.valueArrays = new ValueArrayLookup(this.valueTypes);
        this.identifierRules = new IdentifierRuleLookup(configuration.getIdentifierExtractors());
    }

    /**
     * 判断类型是否为值类型（快照为 {@code PrimitiveNode} 的类型）：原始类型与包装类、String、
     * 枚举、默认值类型包、默认值类型类、配置的自定义值类型包与自定义值类型。
     * <p>
     * 分类结果按类按需解析并复用；实际字段值不缓存，仍读取当前对象。
     *
     * @param type 目标类，不能为 null
     * @return 值类型返回 true
     * @throws NullPointerException 如果 type 为 null
     */
    boolean isValueType(final Class<?> type) {
        Objects.requireNonNull(type, "type");
        return this.valueTypes.get(type);
    }

    /**
     * 判断数组类型是否为值数组：沿组件类型链递归到最底层组件，底层为基本类型或值类型即为值数组，
     * 复杂对象元素数组按集合语义处理。
     * <p>
     * 数组判定依赖当前配置的值类型分类，因此按类按需解析并复用，不能使用跨配置的静态结论。
     *
     * @param type 数组类型，不能为 null
     * @return 值数组返回 true
     * @throws NullPointerException     如果 type 为 null
     * @throws IllegalArgumentException 如果 type 不是数组类型
     */
    boolean isValueArray(final Class<?> type) {
        Objects.requireNonNull(type, "type");
        return this.valueArrays.get(type);
    }

    /**
     * 解析类型的标识规则：先查该类精确 key，再按类链的每一层递归展开接口链（接口与父接口）查找；
     * {@code Object.class} 不作为查找层。未找到提取器时返回显式的 identity 回退规则。
     * <p>
     * 解析结果按类按需解析并复用（含未找到的结果）；提取器本身每次仍由快照策略执行，其返回值不缓存。
     *
     * @param type 目标类，不能为 null
     * @return 该类的标识规则，注册的提取器或 {@link IdentifierRule#IDENTITY_FALLBACK}，永不为 null
     * @throws NullPointerException 如果 type 为 null
     */
    IdentifierRule identifierRule(final Class<?> type) {
        Objects.requireNonNull(type, "type");
        return this.identifierRules.get(type);
    }

    /**
     * 解析后的标识规则：注册的提取器，或未找到提取器时的显式 identity 回退。
     *
     * @param extractor 标识提取函数，不能为 null；identity 回退规则以 identityHashCode 包装为
     *                  {@link Integer} 的函数表达，回退规则是显式实例而非 null
     */
    record IdentifierRule(Function<Object, Object> extractor) {

        /**
         * 未找到提取器时的显式回退规则：{@link System#identityHashCode(Object)} 包装为 {@link Integer}，
         * 与既有标识回退语义一致（标识永不为 null）。
         */
        static final IdentifierRule IDENTITY_FALLBACK =
                new IdentifierRule(target -> System.identityHashCode(target));

        /**
         * 非空校验：规则必须携带可执行函数，null 不做语义载体。
         */
        IdentifierRule {
            Objects.requireNonNull(extractor, "extractor");
        }
    }

    /**
     * 值类型分类的按类缓存：绑定创建它的快照策略的不可变配置。
     */
    private static final class ValueTypeLookup extends ClassValue<Boolean> {

        /** 创建本缓存时绑定的不可变配置。 */
        private final TrackingConfiguration configuration;

        /**
         * 绑定不可变配置创建分类缓存。
         *
         * @param configuration 快照策略持有的不可变追踪配置
         */
        ValueTypeLookup(final TrackingConfiguration configuration) {
            this.configuration = configuration;
        }

        /**
         * 计算该类的值类型分类：按既有判定顺序（原始类型与包装类、String、枚举、默认值类型包、
         * 默认值类型类、自定义值类型包、自定义值类型）解析一次。
         *
         * @param type 目标类
         * @return 值类型返回 true
         */
        @Override
        protected Boolean computeValue(final Class<?> type) {
            if (ReflectionUtils.isPrimitiveOrWrapper(type)) {
                return Boolean.TRUE;
            }
            if (type.equals(String.class)) {
                return Boolean.TRUE;
            }
            if (type.isEnum()) {
                return Boolean.TRUE;
            }
            final String packageName = type.getPackageName();
            if (DEFAULT_VALUE_PACKAGES.contains(packageName)) {
                return Boolean.TRUE;
            }
            if (DEFAULT_VALUE_CLASSES.contains(type)) {
                return Boolean.TRUE;
            }
            if (this.configuration.getCustomValuePackages().contains(packageName)) {
                return Boolean.TRUE;
            }
            return this.configuration.getCustomValueTypes().contains(type);
        }
    }

    /**
     * 值数组分类的按类缓存：复用同一缓存实例的值类型分类，保证数组判定与元素判定使用同一份配置结论。
     */
    private static final class ValueArrayLookup extends ClassValue<Boolean> {

        /** 同一缓存实例的值类型分类，用于判定组件类型链的底层类型。 */
        private final ClassValue<Boolean> valueTypes;

        /**
         * 绑定值类型分类创建数组分类缓存。
         *
         * @param valueTypes 同一缓存实例的值类型分类
         */
        ValueArrayLookup(final ClassValue<Boolean> valueTypes) {
            this.valueTypes = valueTypes;
        }

        /**
         * 计算该数组类型的值数组分类：组件类型链递归到最底层，底层为基本类型或值类型即为值数组。
         *
         * @param type 数组类型
         * @return 值数组返回 true
         */
        @Override
        protected Boolean computeValue(final Class<?> type) {
            if (!type.isArray()) {
                throw new IllegalArgumentException("Not an array type: " + type.getName());
            }
            Class<?> component = type.getComponentType();
            while (component.isArray()) {
                component = component.getComponentType();
            }
            return component.isPrimitive() || this.valueTypes.get(component);
        }
    }

    /**
     * 标识规则的按类缓存：绑定不可变配置中的提取器映射，缓存含未找到结果的解析结论。
     */
    private static final class IdentifierRuleLookup extends ClassValue<IdentifierRule> {

        /** 创建本缓存时绑定的不可变提取器映射。 */
        private final Map<Class<?>, Function<Object, Object>> extractors;

        /**
         * 绑定不可变提取器映射创建规则缓存。
         *
         * @param extractors 不可变配置携带的提取器映射
         */
        IdentifierRuleLookup(final Map<Class<?>, Function<Object, Object>> extractors) {
            this.extractors = extractors;
        }

        /**
         * 计算该类的标识规则：类链 × 每层接口链（接口与父接口，防环）查找，{@code Object.class}
         * 不作为查找层；未找到时返回 {@link IdentifierRule#IDENTITY_FALLBACK}，使未找到的结果同样被复用。
         *
         * @param type 目标类
         * @return 解析出的标识规则，永不为 null
         */
        @Override
        protected IdentifierRule computeValue(final Class<?> type) {
            Class<?> current = type;
            while (current != null && current != Object.class) {
                final Function<Object, Object> exact = this.extractors.get(current);
                if (exact != null) {
                    return new IdentifierRule(exact);
                }
                final IdentifierRule fromInterfaces = findInInterfaceChain(current, new HashSet<>());
                if (fromInterfaces != null) {
                    return fromInterfaces;
                }
                current = current.getSuperclass();
            }
            return IdentifierRule.IDENTITY_FALLBACK;
        }

        /**
         * 递归查找接口链（接口 + 父接口）中注册的提取器；{@link Class#getInterfaces()} 只返回直接接口，
         * 菱形接口链采用访问集防环。
         *
         * @param type    当前层要展开接口链的类型
         * @param visited 已访问接口集合（防环）
         * @return 命中的规则，未命中返回 null
         */
        private IdentifierRule findInInterfaceChain(final Class<?> type, final Set<Class<?>> visited) {
            for (final Class<?> iface : type.getInterfaces()) {
                if (!visited.add(iface)) {
                    continue;
                }
                final Function<Object, Object> exact = this.extractors.get(iface);
                if (exact != null) {
                    return new IdentifierRule(exact);
                }
                final IdentifierRule fromParents = findInInterfaceChain(iface, visited);
                if (fromParents != null) {
                    return fromParents;
                }
            }
            return null;
        }
    }
}