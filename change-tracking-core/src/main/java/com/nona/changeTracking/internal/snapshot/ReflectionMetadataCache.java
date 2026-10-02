package com.nona.changeTracking.internal.snapshot;

/**
 * 共享反射元数据缓存（ADR-002）：按类复用有序非静态字段及其字段访问准备状态。
 * <p>
 * 元数据与 {@code TrackingConfiguration} 无关，因此以进程内共享实例 {@link #SHARED} 提供，
 * 同一类的后续实例、不同 capability 与不同 tracker 复用同一份元数据；配置相关的值类型分类、
 * 值数组分类与标识规则由 {@link ConfiguredTypeRulesCache} 按策略实例隔离，不进入本缓存。
 * <p>
 * 实现继承 JDK {@link ClassValue}：条目挂在对应的 {@link Class} 上，不建立强键全局
 * {@code Map}、弱引用 {@code Map}、引用队列或清理线程；目标类及其加载器满足卸载条件时，
 * 元数据随条目一并回收，存活类保留已缓存元数据（不承诺容量上限或立即回收）。
 * <p>
 * 本缓存不保存业务实例、配置、提取器或字段值：字段值与标识值仍由快照策略按当前对象读取。
 */
final class ReflectionMetadataCache extends ClassValue<ReflectionTypeMetadata> {

    /** 共享实例：类元数据与配置无关，全进程复用一份。 */
    static final ReflectionMetadataCache SHARED = new ReflectionMetadataCache();

    /**
     * 私有构造器：共享缓存只经 {@link #SHARED} 使用。
     */
    private ReflectionMetadataCache() {
    }

    /**
     * 计算首次遇到的类型的元数据：收集该类的有序非静态字段（子类到父类、各类
     * {@code getDeclaredFields()} 返回序），不提前对字段设置访问权限（字段访问仍在实际读取处
     * 首次准备，保持既有异常时机与读取顺序）。
     * <p>
     * 计算可重复且无业务副作用：并发首次访问可能重复计算，结果必须一致。
     *
     * @param type 目标类，不能为 null
     * @return 该类的只读元数据
     */
    @Override
    protected ReflectionTypeMetadata computeValue(final Class<?> type) {
        return ReflectionTypeMetadata.forType(type);
    }
}