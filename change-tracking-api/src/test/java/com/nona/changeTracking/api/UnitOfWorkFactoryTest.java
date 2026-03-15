package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.changeset.ChangeSet;
import com.nona.changeTracking.domain.model.changeset.FieldChange;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UnitOfWorkFactory (API) 测试")
class UnitOfWorkFactoryTest {

    // --- Test Data ---
    static class User {
        String name;
        User(String name) { this.name = name; }
    }

    // --- Mocks & Stubs ---
    // 一个用于测试的、可被 mock 的 Provider 实现
    public static class CustomTestCapabilityProvider implements TrackingCapabilityProvider {
        public static final String NAME = "custom-test-capability";
        @Override public String getName() { return NAME; }
        @Override public <T> TrackingCapabilityProvider withIdentifier(Class<T> type, java.util.function.Function<T, Object> extractor) { return this; }
        @Override public TrackingCapabilityProvider withValueType(Class<?> type) { return this; }
        @Override public TrackingCapabilityProvider withValuePackage(String packageName) { return this; }
        @Override public TrackingCapability create() {
            // 在真实的测试中，create() 的行为会被 mock
            return null;
        }
    }

    @Test
    @DisplayName("默认构建器应能创建一个使用默认能力的 UnitOfWork (端到端)")
    void defaultBuilder_shouldCreateFunctionalUnitOfWork() {
        // 这个测试保持不变，它验证的是 ServiceLoader 能否加载 core 模块自己 provide 的服务
        final UnitOfWork uow = UnitOfWorkFactory.builder().withDefaults().build();
        assertNotNull(uow);

        final User user = new User("Alice");
        uow.registerClean(user);
        user.name = "Alicia";

        final ChangeSet changeSet = uow.calculateChanges();

        assertFalse(changeSet.isEmpty());
        assertEquals(1, changeSet.getLeafChanges().size());
        final FieldChange change = (FieldChange) changeSet.getLeafChanges().get(0);
        assertEquals("name", change.path());
        assertEquals("Alice", change.oldValue());
        assertEquals("Alicia", change.newValue());
    }

    @Test
    @DisplayName("Builder 应能从已加载的 Provider 中选择一个自定义 Capability")
    void builder_shouldSelectCustomCapabilityFromLoadedProviders() {
        // --- Arrange ---
        final TrackingCapabilityProvider defaultProvider = mock(TrackingCapabilityProvider.class);
        when(defaultProvider.getName()).thenReturn("default-reflection");
        final TrackingCapabilityProvider customProvider = mock(TrackingCapabilityProvider.class);
        when(customProvider.getName()).thenReturn(CustomTestCapabilityProvider.NAME);
        // **【核心修正点】** 必须为 create() 方法提供一个返回值
        final TrackingCapability mockCapability = mock(TrackingCapability.class);
        when(customProvider.create()).thenReturn(mockCapability);
        final ServiceLoader<TrackingCapabilityProvider> mockedLoader = mock(ServiceLoader.class);
        when(mockedLoader.iterator()).thenReturn(List.of(defaultProvider, customProvider).iterator());
        try (MockedStatic<ServiceLoader> mockedServiceLoader = mockStatic(ServiceLoader.class)) {
            mockedServiceLoader.when(() -> ServiceLoader.load(TrackingCapabilityProvider.class)).thenReturn(mockedLoader);
            final UnitOfWorkFactory.Builder builder = UnitOfWorkFactory.builder().withDefaults();
            // --- Act ---
            builder.capability(CustomTestCapabilityProvider.NAME);
            final UnitOfWork uow = builder.build();
            // --- Assert ---
            verify(customProvider, times(1)).create();
            verify(defaultProvider, never()).create();
            assertNotNull(uow); // 确保 uow 被成功创建
        }
    }

    @Test
    @DisplayName("选择一个不存在的能力时应抛出异常")
    void build_shouldThrowExceptionForNonExistentCapability() {
        // --- Arrange ---
        final TrackingCapabilityProvider defaultProvider = mock(TrackingCapabilityProvider.class);
        when(defaultProvider.getName()).thenReturn("default-reflection");
        final ServiceLoader<TrackingCapabilityProvider> mockedLoader = mock(ServiceLoader.class);
        when(mockedLoader.iterator()).thenReturn(List.of(defaultProvider).iterator());
        try (MockedStatic<ServiceLoader> mockedServiceLoader = mockStatic(ServiceLoader.class)) {
            mockedServiceLoader.when(() -> ServiceLoader.load(TrackingCapabilityProvider.class)).thenReturn(mockedLoader);
            final UnitOfWorkFactory.Builder builder = UnitOfWorkFactory.builder().withDefaults();
            // --- Act & Assert ---
            final var exception = assertThrows(IllegalArgumentException.class, () -> builder.capability("non-existent-capability").build());
            assertTrue(exception.getMessage().contains("non-existent-capability"));
            assertTrue(exception.getMessage().contains("default-reflection"));
        }
    }

    @Test
    @DisplayName("withDefaults() 在多个 Provider 时默认选择应稳定（优先 default-reflection）")
    void withDefaults_shouldDeterministicallySelectDefaultReflection() {
        final TrackingCapabilityProvider defaultProvider = mock(TrackingCapabilityProvider.class);
        when(defaultProvider.getName()).thenReturn("default-reflection");
        final TrackingCapabilityProvider otherProvider = mock(TrackingCapabilityProvider.class);
        when(otherProvider.getName()).thenReturn("another-provider");

        final TrackingCapability mockCapability = mock(TrackingCapability.class);
        when(defaultProvider.create()).thenReturn(mockCapability);
        when(otherProvider.create()).thenReturn(mockCapability);

        final ServiceLoader<TrackingCapabilityProvider> mockedLoader = mock(ServiceLoader.class);
        // 刻意打乱顺序：让 default-reflection 不是第一个
        when(mockedLoader.iterator()).thenReturn(List.of(otherProvider, defaultProvider).iterator());

        try (MockedStatic<ServiceLoader> mockedServiceLoader = mockStatic(ServiceLoader.class)) {
            mockedServiceLoader.when(() -> ServiceLoader.load(TrackingCapabilityProvider.class)).thenReturn(mockedLoader);

            final UnitOfWork uow = UnitOfWorkFactory.builder().withDefaults().build();

            verify(defaultProvider, times(1)).create();
            verify(otherProvider, never()).create();
            assertNotNull(uow);
        }
    }
}
