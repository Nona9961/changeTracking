package com.nona.changeTracking.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SnapshotStrategyProvider SPI 接口测试")
class SnapshotStrategyProviderTest {

    // 这是一个测试用的、实现了 SnapshotStrategyProvider 接口的模拟类。
    static class TestSnapshotStrategyProvider implements SnapshotStrategyProvider {
        @Override
        public String getName() {
            return "test-strategy";
        }

        @Override
        public SnapshotStrategy create(CreationContext context) {
            // 在测试中，我们可以返回一个简单的匿名实现
            return entity -> null;
        }
    }

    @Test
    @DisplayName("Provider 应能提供一个唯一的名称")
    void provider_shouldProvideUniqueName() {
        // --- Arrange ---
        final SnapshotStrategyProvider provider = new TestSnapshotStrategyProvider();

        // --- Act ---
        final String name = provider.getName();

        // --- Assert ---
        assertNotNull(name, "名称不应为 null");
        assertFalse(name.isBlank(), "名称不应为空白");
    }

    @Test
    @DisplayName("Provider 应能创建一个 SnapshotStrategy 实例")
    void provider_shouldCreateSnapshotStrategyInstance() {
        // --- Arrange ---
        final SnapshotStrategyProvider provider = new TestSnapshotStrategyProvider();
        
        // --- 修正点 ---
        // 将 Lambda 表达式替换为完整的匿名内部类实现
        final CreationContext mockContext = new CreationContext() {
            // 空实现，因为接口是空的
        };

        // --- Act ---
        final SnapshotStrategy strategy = provider.create(mockContext);

        // --- Assert ---
        assertNotNull(strategy, "创建的策略实例不应为 null");
    }
}
