package com.nona.changeTracking.api;

import com.nona.changeTracking.domain.detector.ChangeDetector;
import com.nona.changeTracking.domain.detector.ChangeDetectorBuilder;
import com.nona.changeTracking.domain.model.unitofwork.UnitOfWork;
import com.nona.changeTracking.spi.CreationContext;
import com.nona.changeTracking.spi.SnapshotStrategy;
import com.nona.changeTracking.spi.SnapshotStrategyProvider;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 创建 {@link UnitOfWork} 实例的工厂类。
 * <p>
 * 这是框架的顶层公共 API 入口，封装了所有底层组件的创建和组装细节。
 * 它使用 Builder 模式，并能通过 {@link ServiceLoader} 自动发现和整合 SPI 实现。
 */
public final class UnitOfWorkFactory {

    private UnitOfWorkFactory() {
    }

    /**
     * 创建一个新的构建器实例，用于配置和创建 {@link UnitOfWork}。
     *
     * @return 一个新的 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, SnapshotStrategyProvider> strategyProviders = new HashMap<>();
        private String selectedStrategyName;

        private Builder() {
        }

        /**
         * 使用 {@link ServiceLoader} 自动发现并加载所有在模块路径中可用的
         * {@link SnapshotStrategyProvider} 和 {@link com.nona.changeTracking.spi.SnapshotComparatorProvider} 实现。
         *
         * @return 当前构建器实例，以支持链式调用。
         */
        public Builder withDefaults() {
            final ServiceLoader<SnapshotStrategyProvider> loader = ServiceLoader.load(
                    SnapshotStrategyProvider.class,
                    Thread.currentThread().getContextClassLoader());
            for (final SnapshotStrategyProvider provider : loader) {
                this.strategyProviders.put(provider.getName(), provider);
            }
            // 默认选择第一个发现的策略
            if (this.selectedStrategyName == null && !this.strategyProviders.isEmpty()) {
                this.selectedStrategyName = this.strategyProviders.keySet().iterator().next();
            }
            return this;
        }

        /**
         * 按名称选择要使用的快照策略。
         *
         * @param name 策略的唯一名称，必须与 {@link SnapshotStrategyProvider#getName()} 返回的值匹配。
         * @return 当前构建器实例，以支持链式调用。
         */
        public Builder snapshotStrategy(final String name) {
            this.selectedStrategyName = Objects.requireNonNull(name, "Strategy name cannot be null.");
            return this;
        }

        /**
         * 构建一个配置好的 {@link UnitOfWork} 实例。
         *
         * @return 新的 UnitOfWork 实例。
         * @throws IllegalStateException    如果没有可用的快照策略。
         * @throws IllegalArgumentException 如果选择的快照策略不存在。
         */
        public UnitOfWork build() {
            if (this.strategyProviders.isEmpty()) {
                throw new IllegalStateException("No SnapshotStrategyProviders found. " +
                        "Ensure at least one is available via ServiceLoader or manual registration.");
            }
            if (this.selectedStrategyName == null) {
                throw new IllegalStateException("No snapshot strategy selected.");
            }

            final SnapshotStrategyProvider selectedProvider = this.strategyProviders.get(this.selectedStrategyName);
            if (selectedProvider == null) {
                throw new IllegalArgumentException(String.format(
                        "Snapshot strategy with name '%s' not found. Available strategies: %s",
                        this.selectedStrategyName, this.strategyProviders.keySet()));
            }

            final CreationContext context = new CreationContext() {
            };
            final SnapshotStrategy snapshotStrategy = selectedProvider.create(context);
            final ChangeDetector changeDetector = ChangeDetectorBuilder.create().withDefaults().build();

            return new UnitOfWork(snapshotStrategy, changeDetector);
        }
    }
}
