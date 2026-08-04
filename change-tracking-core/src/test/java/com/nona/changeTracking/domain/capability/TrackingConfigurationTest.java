package com.nona.changeTracking.domain.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TrackingConfiguration 不可变契约测试（WU-A3 新契约）。
 * <p>
 * 契约：
 * <ul>
 *   <li>构造器对传入集合做防御拷贝——构造后外部修改传入集合不影响配置内部状态</li>
 *   <li>getter 返回不可变集合——任何 add/put 抛 {@link UnsupportedOperationException}</li>
 * </ul>
 */
@DisplayName("TrackingConfiguration 不可变契约")
class TrackingConfigurationTest {

    static class Money {
    }

    private static TrackingConfiguration newConfig() {
        return new TrackingConfiguration(
                new HashMap<>(),
                new HashSet<>(),
                new HashSet<>()
        );
    }

    @Test
    @DisplayName("getCustomValueTypes() 返回集合不可变：add 抛 UnsupportedOperationException")
    void getCustomValueTypes_shouldBeImmutable() {
        final TrackingConfiguration config = newConfig();

        assertThrows(UnsupportedOperationException.class,
                () -> config.getCustomValueTypes().add(Money.class));
    }

    @Test
    @DisplayName("getCustomValuePackages() 返回集合不可变：add 抛 UnsupportedOperationException")
    void getCustomValuePackages_shouldBeImmutable() {
        final TrackingConfiguration config = newConfig();

        assertThrows(UnsupportedOperationException.class,
                () -> config.getCustomValuePackages().add("com.example"));
    }

    @Test
    @DisplayName("getIdentifierExtractors() 返回 Map 不可变：put 抛 UnsupportedOperationException")
    void getIdentifierExtractors_shouldBeImmutable() {
        final TrackingConfiguration config = newConfig();

        assertThrows(UnsupportedOperationException.class,
                () -> config.getIdentifierExtractors().put(Money.class, obj -> 1L));
    }

    @Test
    @DisplayName("构造防御拷贝：构造后外部修改传入的 Set 不影响配置内部状态")
    void externalMutation_afterConstruction_shouldNotPolluteConfig() {
        final Set<Class<?>> valueTypes = new HashSet<>();
        final TrackingConfiguration config = new TrackingConfiguration(
                new HashMap<>(),
                valueTypes,
                new HashSet<>()
        );

        valueTypes.add(Money.class);

        assertFalse(config.getCustomValueTypes().contains(Money.class), "构造器应拷贝传入集合，外部修改不可见");
    }

    @Test
    @DisplayName("构造防御拷贝：构造后外部修改传入的 Map 不影响配置内部状态")
    void externalMapMutation_afterConstruction_shouldNotPolluteConfig() {
        final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
        final TrackingConfiguration config = new TrackingConfiguration(
                extractors,
                new HashSet<>(),
                new HashSet<>()
        );
        final Function<Object, Object> extractor = obj -> 1L;

        extractors.put(Money.class, extractor);

        assertFalse(config.getIdentifierExtractors().containsKey(Money.class), "构造器应拷贝传入 Map，外部修改不可见");
    }

    @Test
    @DisplayName("防御拷贝后配置内部状态仍可正常读取（get 语义不受影响）")
    void defensiveCopy_shouldKeepReadSemantics() {
        final Map<Class<?>, Function<Object, Object>> extractors = new HashMap<>();
        final Function<Object, Object> extractor = obj -> 42L;
        extractors.put(Money.class, extractor);

        final TrackingConfiguration config = new TrackingConfiguration(
                extractors,
                Set.of(Money.class),
                Set.of("com.example")
        );

        assertEquals(extractor, config.getIdentifierExtractors().get(Money.class));
        assertTrue(config.getCustomValueTypes().contains(Money.class));
        assertTrue(config.getCustomValuePackages().contains("com.example"));
        assertDoesNotThrow(config::getCustomValueTypes);
    }

    @Test
    @DisplayName("空配置单例 empty() 的集合同样不可变")
    void emptyConfig_shouldBeImmutable() {
        final TrackingConfiguration empty = TrackingConfiguration.empty();

        assertThrows(UnsupportedOperationException.class,
                () -> empty.getCustomValueTypes().add(Money.class));
        assertThrows(UnsupportedOperationException.class,
                () -> empty.getIdentifierExtractors().put(Money.class, obj -> 1L));
        assertThrows(UnsupportedOperationException.class,
                () -> empty.getCustomValuePackages().add("com.example"));
    }
}
