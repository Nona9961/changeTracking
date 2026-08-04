package com.nona.changeTracking.domain.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TrackingConfiguration 不可变性特征测试。
 * <p>
 * 现状（特征）：构造器直接引用调用方传入的集合、不拷贝；
 * getter 直接返回内部引用（可能已被外部修改）。
 * 以下测试固化该「假不可变」现状行为，供 WU-A3 修复时改写断言。
 */
@DisplayName("TrackingConfiguration 不可变性特征测试（现状：假不可变）")
class TrackingConfigurationTest {

    static class Money {
    }

    @Test
    @DisplayName("getCustomValueTypes() 返回的集合现状可直接修改（特征：可变泄漏）")
    void getCustomValueTypes_shouldBeMutableInCurrentImplementation() {
        final TrackingConfiguration config = new TrackingConfiguration(
                new HashMap<>(),
                new HashSet<>(),
                new HashSet<>()
        );

        final boolean added = config.getCustomValueTypes().add(Money.class);

        assertTrue(added, "特征：getter 返回的集合可写");
        assertTrue(config.getCustomValueTypes().contains(Money.class));
    }

    @Test
    @DisplayName("构造后修改外部传入的集合会污染配置（特征：构造器不拷贝）")
    void externalMutation_afterConstruction_shouldPolluteConfig() {
        final Set<Class<?>> valueTypes = new HashSet<>();
        final TrackingConfiguration config = new TrackingConfiguration(
                new HashMap<>(),
                valueTypes,
                new HashSet<>()
        );

        valueTypes.add(Money.class);

        assertTrue(config.getCustomValueTypes().contains(Money.class), "特征：外部修改可见于配置");
    }

    @Test
    @DisplayName("getIdentifierExtractors() 返回的 Map 现状可直接修改（特征：可变泄漏）")
    void getIdentifierExtractors_shouldBeMutableInCurrentImplementation() {
        final TrackingConfiguration config = new TrackingConfiguration(
                new HashMap<>(),
                new HashSet<>(),
                new HashSet<>()
        );
        final Function<Object, Object> extractor = obj -> 1L;

        config.getIdentifierExtractors().put(Money.class, extractor);

        assertEquals(extractor, config.getIdentifierExtractors().get(Money.class), "特征：extractors 可写");
    }
}