package com.nona.changeTracking.internal.snapshot;

import com.nona.changeTracking.internal.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 一个类的反射元数据（ADR-002）：只读提供该类的有序非静态字段及其字段访问准备状态。
 * <p>
 * 字段顺序沿用既有遍历：子类到父类，各类内保持 {@code getDeclaredFields()} 的返回顺序；静态字段
 * 在收集时排除；字段隐藏（子类与父类同名字段）不在此处合并，两个字段都保留并按顺序出现，由快照
 * 策略按既有 {@code putIfAbsent} 规则保留先读到的值。
 * <p>
 * 字段访问准备遵循“首次实际遇到该字段时准备”的时序：收集元数据不调用
 * {@code Field#setAccessible(boolean)}，访问准备在 {@link #access(int)} 首次调用时按原遍历顺序发生，
 * 成功后复用同一访问对象；准备失败不记为成功（后续调用仍按失败处理），异常在实际读取处产生。
 * {@link ReflectionFieldAccess} 的发布对并发读取安全。
 */
final class ReflectionTypeMetadata {

    /**
     * 有序非静态字段：子类到父类，各类内保持 {@code getDeclaredFields()} 的返回顺序。
     */
    private final Field[] fields;

    /**
     * 按位置的字段访问准备结果：首次 {@link #access(int)} 成功时写入，未准备的位置为 null。
     * <p>
     * {@link AtomicReferenceArray} 提供易失读写语义，使并发读取不会看到未安全发布的访问对象；
     * 准备本身在 {@link #prepare(int)} 的监视器内串行，因此同一位置的两个并发首次访问也会得到同一实例。
     */
    private final AtomicReferenceArray<ReflectionFieldAccess> accesses;

    /**
     * 私有构造器：元数据只经 {@link #forType(Class)} 或 {@link ReflectionMetadataCache} 构造。
     *
     * @param fields 已收集的有序非静态字段
     */
    private ReflectionTypeMetadata(final Field[] fields) {
        this.fields = fields;
        this.accesses = new AtomicReferenceArray<>(fields.length);
    }

    /**
     * 按类收集元数据：有序非静态字段（子类到父类、声明序），不准备字段访问。
     *
     * @param type 目标类，不能为 null
     * @return 该类的只读元数据
     */
    static ReflectionTypeMetadata forType(final Class<?> type) {
        final List<Field> collected = new ArrayList<>();
        for (final Field field : ReflectionUtils.getAllFields(type)) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            collected.add(field);
        }
        return new ReflectionTypeMetadata(collected.toArray(new Field[0]));
    }

    /**
     * 返回元数据中的字段数量。
     *
     * @return 非静态字段数量，无字段的类返回 0
     */
    int size() {
        return this.fields.length;
    }

    /**
     * 返回指定位置字段的访问准备结果：首次调用时准备该字段的访问（{@code setAccessible}），
     * 成功后同一位置重复调用返回同一实例。
     *
     * @param index 字段位置，取值范围 [0, {@link #size()})，顺序为子类到父类的声明序
     * @return 该字段的访问准备结果
     * @throws IndexOutOfBoundsException 如果 index 超出字段范围
     * @throws java.lang.reflect.InaccessibleObjectException 如果该字段无法准备访问（准备失败不被记为成功）
     */
    ReflectionFieldAccess access(final int index) {
        final ReflectionFieldAccess prepared = this.accesses.get(index);
        if (prepared != null) {
            return prepared;
        }
        return prepare(index);
    }

    /**
     * 首次准备一个位置的字段访问并发布结果；准备失败时不写入任何访问对象，因此后续调用仍会重新准备
     * 并把失败暴露在实际读取处，失败不会被记为成功。
     *
     * @param index 已通过范围检查的字段位置
     * @return 该位置的访问准备结果
     * @throws java.lang.reflect.InaccessibleObjectException 如果该字段无法准备访问
     */
    private synchronized ReflectionFieldAccess prepare(final int index) {
        final ReflectionFieldAccess existing = this.accesses.get(index);
        if (existing != null) {
            return existing;
        }
        final Field field = this.fields[index];
        field.setAccessible(true);
        final ReflectionFieldAccess access = new ReflectionFieldAccess(field);
        this.accesses.set(index, access);
        return access;
    }

    /**
     * 一个字段的访问准备结果：字段名与已准备的读取能力。
     * <p>
     * 访问对象不保存业务实例与字段值：{@link #read(Object)} 每次按当前对象读取，因此 track 之后
     * 的业务修改不会被快照缓存掩盖。
     */
    static final class ReflectionFieldAccess {

        /** 已准备访问权限的字段，读取时按当前对象取值。 */
        private final Field field;

        /**
         * 私有构造器：访问对象只由所属元数据在准备成功时构造。
         *
         * @param field 已成功准备访问权限的字段
         */
        private ReflectionFieldAccess(final Field field) {
            this.field = field;
        }

        /**
         * 返回字段名，用于快照字段键与既有失败消息。
         *
         * @return 字段名，不含类名限定
         */
        String fieldName() {
            return this.field.getName();
        }

        /**
         * 读取指定对象的该字段当前值。
         *
         * @param target 目标对象，不能为 null
         * @return 该字段的当前值（可为 null）
         * @throws NullPointerException 如果 target 为 null
         * @throws IllegalAccessException 如果字段不可访问（由调用方按既有约定包装为
         *                                {@link IllegalStateException}）
         */
        Object read(final Object target) throws IllegalAccessException {
            return this.field.get(target);
        }
    }
}