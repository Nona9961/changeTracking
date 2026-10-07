package com.nona.changeTracking.bench.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetainedFootprint} and its {@link ClassFootprint} entries: the value
 * contract, the defensive copy and the read only class list.
 */
@DisplayName("RetainedFootprint 保留脚印值对象单元测试")
class RetainedFootprintUnitTest {

    @Test
    @DisplayName("类脚印应校验类名、对象数与字节数")
    void classFootprint_shouldValidateItsComponents() {
        assertThat(new ClassFootprint("java.lang.String", 2, 48L).objectCount()).isEqualTo(2);

        assertThatThrownBy(() -> new ClassFootprint(null, 1, 16L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ClassFootprint("  ", 1, 16L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassFootprint("java.lang.String", 0, 16L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassFootprint("java.lang.String", 1, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("脚印应校验总字节数、对象数与类列表")
    void retainedFootprint_shouldValidateItsComponents() {
        final List<ClassFootprint> classes = new ArrayList<>();
        classes.add(new ClassFootprint("java.lang.String", 2, 48L));

        final RetainedFootprint footprint = new RetainedFootprint(48L, 2, classes);

        assertThat(footprint.retainedBytes()).isEqualTo(48L);
        assertThat(footprint.objectCount()).isEqualTo(2);

        assertThatThrownBy(() -> new RetainedFootprint(-1L, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainedFootprint(0L, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetainedFootprint(0L, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("脚印应防御性复制并只读类列表")
    void retainedFootprint_shouldDefensivelyCopyClassList() {
        final List<ClassFootprint> classes = new ArrayList<>();
        classes.add(new ClassFootprint("java.lang.String", 1, 24L));
        final RetainedFootprint footprint = new RetainedFootprint(24L, 1, classes);

        classes.add(new ClassFootprint("java.lang.Integer", 1, 16L));

        assertThat(footprint.classFootprints()).hasSize(1);
        assertThatThrownBy(() -> footprint.classFootprints().add(new ClassFootprint("java.lang.Long", 1, 24L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
