package com.nona.changeTracking.spi;

import com.nona.changeTracking.domain.model.unitofwork.Snapshot;

/**
 * 一个服务提供者接口 (SPI)，定义了如何为领域对象创建状态快照的策略。
 * <p>
 * 这是框架核心领域逻辑（例如 {@code UnitOfWork}）所依赖的抽象。
 * 框架的使用者或适配器模块可以提供此接口的具体实现，以支持不同的
 * 快照技术（例如，基于反射的数据提取、基于序列化的深拷贝等）。
 * <p>
 * 这是一个 {@link FunctionalInterface}，允许使用 lambda 表达式或方法引用来创建简单的实现。
 */
@FunctionalInterface
public interface SnapshotStrategy {

    /**
     * 为给定的领域对象创建一个状态快照。
     * <p>
     * 实现者需要保证返回的 {@link Snapshot} 容器中的数据是与原始 {@code object}
     * 完全解耦的。这意味着对原始对象的任何后续修改，都不应影响到已创建快照中的数据。
     *
     * @param object 需要创建快照的非空领域对象。
     * @return 一个包含对象状态快照的 {@link Snapshot} 实例。返回的具体类型
     *         （例如 {@code MapSnapshot} 或 {@code ObjectSnapshot}）由策略的实现决定。
     * @throws NullPointerException 如果 {@code object} 为 null。
     * @throws SnapshotCreationException 如果在创建快照过程中发生任何不可恢复的错误。
     */
    Snapshot<?> createSnapshot(Object object);

}
 