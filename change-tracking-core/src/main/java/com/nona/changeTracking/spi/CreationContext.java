package com.nona.changeTracking.spi;

/**
 * 一个上下文接口，用于在通过 {@link SnapshotComparatorProvider} 创建服务实例时，
 * 向其传递可能需要的依赖项（如配置、其他服务等）。
 * <p>
 * 在当前版本的框架中，此上下文为空，但为未来的依赖注入功能提供了扩展点。
 */
public interface CreationContext {
    // 初始版本为空，为未来扩展保留。
}
