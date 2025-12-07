package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * 创建 {@link UnitOfWork} 实例的工厂类。
 * <p>
 * 这是框架的顶层公共 API 入口，封装了所有底层组件的创建和组装细节。
 * 它使用 Builder 模式，并能通过 {@link ServiceLoader} 自动发现和整合 SPI 实现。
 */
public final class UnitOfWorkFactory {

    /**
     * 私有构造函数，防止实例化。
     * <p>
     * 此类仅通过静态方法 {@link #builder()} 提供功能。
     */
    private UnitOfWorkFactory() {}

    /**
     * 创建一个新的构建器实例，用于配置和创建 {@link UnitOfWork}。
     *
     * @return 一个新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link UnitOfWork} 的构建器。
     * <p>
     * 提供流式 API 来配置和创建 UnitOfWork 实例。
     * 支持通过 SPI 自动发现追踪能力，也支持手动选择特定的追踪能力。
     */
    public static final class Builder {

        private final Map<String, TrackingCapabilityProvider> providers = new HashMap<>();
        private String selectedCapabilityName;

        /**
         * 私有构造函数，仅通过 {@link UnitOfWorkFactory#builder()} 创建实例。
         */
        private Builder() {}

        /**
         * 使用 {@link ServiceLoader} 自动发现并加载所有在模块路径中可用的
         * {@link TrackingCapabilityProvider} 实现。
         * <p>
         * 如果没有手动选择能力，将默认使用发现的第一个 Provider。
         *
         * @return 当前构建器实例，以支持链式调用。
         */
        public Builder withDefaults() {
            final ServiceLoader<TrackingCapabilityProvider> loader = ServiceLoader.load(TrackingCapabilityProvider.class);
            for (final TrackingCapabilityProvider provider : loader) {
                this.providers.put(provider.getName(), provider);
            }
            // 默认选择第一个发现的 provider (通常是 'default-reflection')
            if (this.selectedCapabilityName == null && !this.providers.isEmpty()) {
                this.selectedCapabilityName = this.providers.keySet().iterator().next();
            }
            return this;
        }

        /**
         * 按名称选择要使用的追踪能力。
         *
         * @param name 追踪能力的唯一名称，必须与 {@link TrackingCapabilityProvider#getName()} 返回的值匹配。
         * @return 当前构建器实例，以支持链式调用。
         */
        public Builder capability(final String name) {
            this.selectedCapabilityName = Objects.requireNonNull(name, "Capability name cannot be null.");
            return this;
        }

        /**
         * 构建一个配置好的 {@link UnitOfWork} 实例。
         *
         * @return 新的 UnitOfWork 实例。
         * @throws IllegalStateException 如果没有可用的追踪能力。
         * @throws IllegalArgumentException 如果选择的追踪能力不存在。
         */
        public UnitOfWork build() {
            if (this.providers.isEmpty()) {
                throw new IllegalStateException("No TrackingCapabilityProviders found. " +
                        "Ensure at least one is available via ServiceLoader.");
            }
            if (this.selectedCapabilityName == null) {
                throw new IllegalStateException("No tracking capability selected.");
            }

            final TrackingCapabilityProvider selectedProvider = this.providers.get(this.selectedCapabilityName);
            if (selectedProvider == null) {
                throw new IllegalArgumentException(String.format(
                        "Tracking capability with name '%s' not found. Available capabilities: %s",
                        this.selectedCapabilityName, this.providers.keySet()));
            }

            final TrackingCapability<?> capability = selectedProvider.create();
            return new UnitOfWork(capability);
        }
    }
}
