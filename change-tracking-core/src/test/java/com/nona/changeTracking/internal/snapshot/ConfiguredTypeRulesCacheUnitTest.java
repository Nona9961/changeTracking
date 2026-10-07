package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.domain.capability.TrackingConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfiguredTypeRulesCache} 的场景测试：值类型分类、值数组分类、标识规则解析与复用，
 * 配置之间的隔离，以及空值与非法输入边界。
 * <p>
 * 每个用例自行构造配置与缓存实例作为前置状态，不依赖其他用例的缓存内容。
 */
@DisplayName("ConfiguredTypeRulesCache 配置规则缓存单元测试")
class ConfiguredTypeRulesCacheUnitTest {

    /**
     * 普通复杂对象探针：未注册任何配置时不是值类型。
     */
    static class PlainObject {

        String text = "plain";
    }

    /**
     * 自定义值类型探针：由配置注册为值类型。
     */
    static final class CustomValueType {

        private final String text;

        /**
         * 创建自定义值类型探针。
         *
         * @param text 载荷文本
         */
        CustomValueType(final String text) {
            this.text = text;
        }

        /**
         * 返回载荷文本。
         *
         * @return 载荷文本
         */
        String text() {
            return text;
        }
    }

    /**
     * 标识接口探针。
     */
    interface Identified {

        /**
         * 返回业务标识。
         *
         * @return 业务标识
         */
        Long id();
    }

    /**
     * 实现标识接口的父类探针。
     */
    static class IdentifiedBase implements Identified {

        /** {@inheritDoc} */
        @Override
        public Long id() {
            return 1L;
        }
    }

    /**
     * 未声明接口的子类探针：接口注册必须沿类链命中。
     */
    static class IdentifiedSub extends IdentifiedBase {
    }

    /**
     * 标识接口的子接口探针。
     */
    interface ChildIdentified extends Identified {
    }

    /**
     * 只实现子接口的探针：父接口注册必须沿接口链命中。
     */
    static class ChildIdentifiedImpl implements ChildIdentified {

        /** {@inheritDoc} */
        @Override
        public Long id() {
            return 2L;
        }
    }

    /**
     * 值类型包注册的包内探针。
     */
    static class PackageValueType {

        String text = "package-value";
    }

    /**
     * 默认按值类型分类的枚举探针。
     */
    enum SampleEnum {
        FIRST
    }

    /**
     * 标识接口的子接口探针。
     */
    interface LeftLeaf extends Identified {
    }

    /**
     * 菱形接口链探针的右侧叶子接口。
     */
    interface RightLeaf extends Identified {
    }

    /**
     * 菱形接口链探针：同时实现两条汇聚到同一父接口的接口。
     */
    static class DiamondImpl implements LeftLeaf, RightLeaf {

        /** {@inheritDoc} */
        @Override
        public Long id() {
            return 3L;
        }
    }

    @Nested
    @DisplayName("值类型分类")
    class ValueTypeClassification {

        @Test
        @DisplayName("原始类型、包装类、String、枚举与默认值类型应按值类型分类")
        void defaultValueTypes_shouldBeClassifiedAsValueTypes() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThat(cache.isValueType(int.class)).isTrue();
            assertThat(cache.isValueType(Integer.class)).isTrue();
            assertThat(cache.isValueType(char.class)).isTrue();
            assertThat(cache.isValueType(boolean.class)).isTrue();
            assertThat(cache.isValueType(String.class)).isTrue();
            assertThat(cache.isValueType(SampleEnum.class)).isTrue();
        }

        @Test
        @DisplayName("默认值类型包与默认值类型类应按值类型分类")
        void defaultValuePackagesAndClasses_shouldBeClassifiedAsValueTypes() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThat(cache.isValueType(LocalDateTime.class)).isTrue();
            assertThat(cache.isValueType(BigDecimal.class)).isTrue();
            assertThat(cache.isValueType(UUID.class)).isTrue();
            assertThat(cache.isValueType(Locale.class)).isTrue();
            assertThat(cache.isValueType(Currency.class)).isTrue();
            assertThat(cache.isValueType(Pattern.class)).isTrue();
            assertThat(cache.isValueType(File.class)).isTrue();
            assertThat(cache.isValueType(Path.class)).isTrue();
        }

        @Test
        @DisplayName("配置的自定义值类型与自定义值类型包应按值类型分类")
        void registeredValueTypesAndPackages_shouldBeClassifiedAsValueTypes() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(CustomValueType.class),
                    Set.of(PackageValueType.class.getPackageName()));

            assertThat(cache.isValueType(CustomValueType.class)).isTrue();
            assertThat(cache.isValueType(PackageValueType.class)).isTrue();
        }

        @Test
        @DisplayName("未注册的复杂对象、数组与集合应按非值类型分类")
        void unregisteredComplexTypes_shouldNotBeValueTypes() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThat(cache.isValueType(PlainObject.class)).isFalse();
            assertThat(cache.isValueType(Object.class)).isFalse();
            assertThat(cache.isValueType(String[].class)).isFalse();
            assertThat(cache.isValueType(Collection.class)).isFalse();
            assertThat(cache.isValueType(List.class)).isFalse();
        }
    }

    @Nested
    @DisplayName("值数组分类")
    class ValueArrayClassification {

        @Test
        @DisplayName("基本类型与值类型元素的一维及多维数组应按值数组分类")
        void valueElementArrays_shouldBeValueArrays() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(CustomValueType.class), Set.of());

            assertThat(cache.isValueArray(int[].class)).isTrue();
            assertThat(cache.isValueArray(Integer[].class)).isTrue();
            assertThat(cache.isValueArray(String[].class)).isTrue();
            assertThat(cache.isValueArray(int[][].class)).isTrue();
            assertThat(cache.isValueArray(CustomValueType[].class)).isTrue();
        }

        @Test
        @DisplayName("复杂对象元素数组（含多维）应按非值数组分类")
        void complexElementArrays_shouldNotBeValueArrays() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThat(cache.isValueArray(Object[].class)).isFalse();
            assertThat(cache.isValueArray(PlainObject[].class)).isFalse();
            assertThat(cache.isValueArray(PlainObject[][].class)).isFalse();
        }

        @Test
        @DisplayName("配置变更应影响同类型的数组判定：注册值类型后元素数组按值数组分类")
        void arrayClassification_shouldFollowTheOwnConfiguration() {
            final ConfiguredTypeRulesCache withoutRegistration = cacheOf(Map.of(), Set.of(), Set.of());
            final ConfiguredTypeRulesCache withRegistration =
                    cacheOf(Map.of(), Set.of(CustomValueType.class), Set.of());

            assertThat(withoutRegistration.isValueArray(CustomValueType[].class)).isFalse();
            assertThat(withRegistration.isValueArray(CustomValueType[].class)).isTrue();
        }
    }

    @Nested
    @DisplayName("标识规则解析")
    class IdentifierRuleResolution {

        @Test
        @DisplayName("精确类型注册应返回注册的提取器")
        void exactTypeRegistration_shouldReturnTheRegisteredExtractor() {
            final Function<Object, Object> extractor = target -> ((IdentifiedBase) target).id();
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(IdentifiedBase.class, extractor), Set.of(), Set.of());

            assertThat(cache.identifierRule(IdentifiedBase.class).extractor()).isSameAs(extractor);
        }

        @Test
        @DisplayName("父类注册应对子类命中，接口注册应沿类链命中未声明接口的子类")
        void registrations_shouldMatchAlongTheClassChainAndInterfaceChain() {
            final Function<Object, Object> extractor = target -> ((Identified) target).id();
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(Identified.class, extractor), Set.of(), Set.of());

            assertThat(cache.identifierRule(IdentifiedSub.class).extractor()).isSameAs(extractor);
            assertThat(cache.identifierRule(ChildIdentifiedImpl.class).extractor()).isSameAs(extractor);
        }

        @Test
        @DisplayName("未注册提取器的类型应返回显式 identity 回退规则，且重复解析复用同一实例")
        void unregisteredType_shouldReturnTheExplicitIdentityFallback() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            final ConfiguredTypeRulesCache.IdentifierRule first = cache.identifierRule(PlainObject.class);
            final ConfiguredTypeRulesCache.IdentifierRule second = cache.identifierRule(PlainObject.class);

            assertThat(first).isSameAs(ConfiguredTypeRulesCache.IdentifierRule.IDENTITY_FALLBACK);
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("identity 回退规则应把 identityHashCode 包装为 Integer")
        void identityFallbackRule_shouldWrapIdentityHashCodeAsInteger() {
            final Object target = new Object();

            final Object identifier = ConfiguredTypeRulesCache.IdentifierRule.IDENTITY_FALLBACK
                    .extractor().apply(target);

            assertThat(identifier).isEqualTo(System.identityHashCode(target));
        }

        @Test
        @DisplayName("Object.class 上注册的提取器不参与查找（Object 不是查找层）")
        void objectClassRegistration_shouldNotBeSearched() {
            final Function<Object, Object> objectExtractor = target -> "object-extractor";
            final ConfiguredTypeRulesCache cache =
                    cacheOf(Map.of(Object.class, objectExtractor), Set.of(), Set.of());

            assertThat(cache.identifierRule(IdentifiedSub.class))
                    .isSameAs(ConfiguredTypeRulesCache.IdentifierRule.IDENTITY_FALLBACK);
        }

        @Test
        @DisplayName("菱形接口链下的查找应终止并命中父接口注册")
        void diamondInterfaceChain_shouldTerminate() {
            final Function<Object, Object> extractor = target -> ((Identified) target).id();
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(Identified.class, extractor), Set.of(), Set.of());

            assertThat(cache.identifierRule(DiamondImpl.class).extractor()).isSameAs(extractor);
        }
    }

    @Nested
    @DisplayName("配置隔离")
    class ConfigurationIsolation {

        @Test
        @DisplayName("两个配置的缓存应各自独立：值类型、值数组与标识规则互不污染")
        void alternatingConfigurations_shouldKeepRulesIndependent() {
            final Function<Object, Object> extractor = target -> ((IdentifiedBase) target).id();
            final ConfiguredTypeRulesCache configured =
                    cacheOf(Map.of(IdentifiedBase.class, extractor), Set.of(CustomValueType.class), Set.of());
            final ConfiguredTypeRulesCache plain = cacheOf(Map.of(), Set.of(), Set.of());

            assertThat(configured.isValueType(CustomValueType.class)).isTrue();
            assertThat(plain.isValueType(CustomValueType.class)).isFalse();
            assertThat(configured.isValueArray(CustomValueType[].class)).isTrue();
            assertThat(plain.isValueArray(CustomValueType[].class)).isFalse();
            assertThat(configured.identifierRule(IdentifiedBase.class).extractor()).isSameAs(extractor);
            assertThat(plain.identifierRule(IdentifiedBase.class))
                    .isSameAs(ConfiguredTypeRulesCache.IdentifierRule.IDENTITY_FALLBACK);
        }

        @Test
        @DisplayName("同一配置的两次查找应复用同一规则实例")
        void sameConfiguration_shouldReuseResolvedRules() {
            final Function<Object, Object> extractor = target -> ((IdentifiedBase) target).id();
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(IdentifiedBase.class, extractor), Set.of(), Set.of());

            assertThat(cache.identifierRule(IdentifiedSub.class)).isSameAs(cache.identifierRule(IdentifiedSub.class));
            assertThat(cache.isValueType(CustomValueType.class)).isEqualTo(cache.isValueType(CustomValueType.class));
        }
    }

    @Nested
    @DisplayName("非法输入与空值")
    class InvalidInput {

        @Test
        @DisplayName("null 配置应被拒绝")
        void nullConfiguration_shouldBeRejected() {
            assertThatThrownBy(() -> new ConfiguredTypeRulesCache(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null 类型应被三个判定入口拒绝")
        void nullType_shouldBeRejectedByEveryRuleEntry() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThatThrownBy(() -> cache.isValueType(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> cache.isValueArray(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> cache.identifierRule(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("非数组类型应被值数组判定拒绝")
        void nonArrayType_shouldBeRejectedByTheValueArrayRule() {
            final ConfiguredTypeRulesCache cache = cacheOf(Map.of(), Set.of(), Set.of());

            assertThatThrownBy(() -> cache.isValueArray(String.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("array");
            assertThatThrownBy(() -> cache.isValueArray(int.class))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("array");
        }

        @Test
        @DisplayName("标识规则不接受 null 提取器")
        void nullExtractor_shouldBeRejected() {
            assertThatThrownBy(() -> new ConfiguredTypeRulesCache.IdentifierRule(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * 用给定配置构造规则缓存。
     *
     * @param extractors     标识提取器映射
     * @param valueTypes     自定义值类型集合
     * @param valuePackages  自定义值类型包集合
     * @return 绑定该配置的规则缓存
     */
    private static ConfiguredTypeRulesCache cacheOf(final Map<Class<?>, Function<Object, Object>> extractors,
                                                    final Set<Class<?>> valueTypes,
                                                    final Set<String> valuePackages) {
        return new ConfiguredTypeRulesCache(new TrackingConfiguration(extractors, valueTypes, valuePackages));
    }
}