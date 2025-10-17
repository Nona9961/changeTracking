package com.nona.changeTracking.spi;

import com.nona.changeTracking.domain.model.snapshot.Snapshot;

/**
 * 服务提供者接口 (SPI)，用于为给定的对象创建状态快照。
 * <p>
 * 这是框架的核心扩展点之一，允许用户插入自定义的快照创建逻辑，
 * 例如基于JSON序列化、Kryo序列化或特定的DTO转换。
 */
public interface SnapshotStrategy {

    /**
     * 为给定的实体对象创建一个快照。
     *
     * @param entity 要创建快照的对象，不能为 null。
     * @return 一个代表该对象当前状态的 {@link Snapshot} 实例。
     * @throws RuntimeException 如果在创建快照过程中发生不可恢复的错误。
     */
    Snapshot<?> createSnapshot(Object entity);
}
