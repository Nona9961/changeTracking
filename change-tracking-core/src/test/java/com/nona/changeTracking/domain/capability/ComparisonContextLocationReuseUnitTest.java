package com.nona.changeTracking.domain.capability;

import com.nona.changeTracking.domain.model.changeset.ChangeLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ComparisonContext} 定位复用单元测试：活动路径上的定位按深度复用、不逐节点从根重建。
 * <p>
 * 覆盖三类场景：同深度重复取定位复用缓存实例（happy）、深入后回退复用祖先实例（critical）、
 * 压入不同段后取定位反映新段（fail 侧的反向约束）。每例由 {@link #setUp()} 自建前置状态。
 * <p>
 * 断言基于定位实例的身份复用：契约（{@link ChangeLocation} 的值语义）不承诺跨入口引用相等，
 * 但「同一活动路径上每个深度最多构造一次定位」是本次定位按需构造与前缀共享的架构约束，
 * 须以身份证据验证，而不是只看取值。
 */
@DisplayName("ComparisonContext 定位复用单元测试")
class ComparisonContextLocationReuseUnitTest {

    /**
     * 每次测试新建的会话状态。
     */
    private ComparisonContext context;

    @BeforeEach
    void setUp() {
        context = new ComparisonContext();
    }

    @Nested
    @DisplayName("同一深度复用")
    class SameDepthReuse {

        @Test
        @DisplayName("空栈重复取定位返回同一实例")
        void repeatedRootLocation_shouldReturnTheSameInstance() {
            assertThat(context.currentLocation()).isSameAs(context.currentLocation());
        }

        @Test
        @DisplayName("同一深度重复取定位返回同一实例")
        void repeatedCurrentLocationAtTheSameDepth_shouldReturnTheSameInstance() {
            context.pushField("address");
            context.pushField("street");
            try {
                assertThat(context.currentLocation()).isSameAs(context.currentLocation());
            } finally {
                context.pop();
                context.pop();
            }
        }

        @Test
        @DisplayName("同深度多次取定位的取值保持一致")
        void repeatedCurrentLocation_shouldKeepTheSameValue() {
            context.pushField("items");
            context.pushItem("A", 2);
            try {
                final ChangeLocation first = context.currentLocation();
                final ChangeLocation second = context.currentLocation();

                assertThat(second.fullPath()).isEqualTo(first.fullPath());
                assertThat(second.relativePath()).isEqualTo(first.relativePath());
                assertThat(second.collectionFieldName()).isEqualTo(first.collectionFieldName());
            } finally {
                context.pop();
                context.pop();
            }
        }
    }

    @Nested
    @DisplayName("前缀复用")
    class PrefixReuse {

        @Test
        @DisplayName("深入一层后回退，祖先定位实例仍被复用")
        void deeperThenPopped_shouldReuseTheAncestorLocation() {
            context.pushField("address");
            final ChangeLocation address = context.currentLocation();
            context.pushField("street");
            assertThat(context.currentLocation().fullPath()).isEqualTo("address.street");

            context.pop();

            assertThat(context.currentLocation()).isSameAs(address);
            context.pop();
        }

        @Test
        @DisplayName("深链逐层取定位后逐层回退，各层实例均被复用")
        void deepChainWalkBack_shouldReuseEveryLevelInstance() {
            final ChangeLocation[] built = new ChangeLocation[16];
            for (int depth = 0; depth < built.length; depth++) {
                context.pushField("level" + depth);
                built[depth] = context.currentLocation();
            }

            for (int depth = built.length - 1; depth >= 0; depth--) {
                assertThat(context.currentLocation()).isSameAs(built[depth]);
                context.pop();
            }
        }
    }

    @Nested
    @DisplayName("段变更后的定位取值")
    class ChangedSegment {

        @Test
        @DisplayName("同深度压入不同段后取定位反映新段")
        void pushingADifferentSegment_shouldDeriveTheNewLocation() {
            context.pushField("first");
            assertThat(context.currentLocation().fullPath()).isEqualTo("first");
            context.pop();

            context.pushField("second");

            assertThat(context.currentLocation().fullPath()).isEqualTo("second");
            context.pop();
        }

        @Test
        @DisplayName("同深度压入不同集合项后取定位反映新标识")
        void pushingADifferentItemIdentity_shouldDeriveTheNewLocation() {
            context.pushField("items");
            context.pushItem("A", ComparisonContext.NO_OCCURRENCE);
            assertThat(context.currentLocation().fullPath()).isEqualTo("items[A]");
            context.pop();

            context.pushItem("B", ComparisonContext.NO_OCCURRENCE);

            assertThat(context.currentLocation().fullPath()).isEqualTo("items[B]");
            context.pop();
            context.pop();
        }
    }
}
