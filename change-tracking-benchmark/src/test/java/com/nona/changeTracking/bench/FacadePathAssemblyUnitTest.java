package com.nona.changeTracking.bench;

import com.nona.changeTracking.api.ChangeTrackerFactory;
import com.nona.changeTracking.bench.FacadeBenchmark.DirectPath;
import com.nona.changeTracking.bench.FacadeBenchmark.FacadePath;
import com.nona.changeTracking.bench.sample.SampleFamily;
import com.nona.changeTracking.bench.sample.SampleMutator;
import com.nona.changeTracking.bench.sample.SampleShape;
import com.nona.changeTracking.domain.capability.TrackingCapability;
import com.nona.changeTracking.domain.model.tracking.ChangeTracker;
import com.nona.changeTracking.spi.TrackingCapabilityProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the assembly paths of {@link FacadeBenchmark}: the direct path discovers its
 * provider through the real SPI instead of the facade, the provider selection of the direct path
 * repeats the documented facade strategy (prefer {@code default-reflection}, otherwise the
 * alphabetically first name), the direct path rejects an empty provider set and propagates a provider
 * creation failure instead of swallowing it, both paths assemble trackers that behave equivalently on
 * the same input shape, and both paths reject an undiscoverable provider with the same failure
 * semantics.
 * <p>
 * The facade side of the pairing is exercised through the public api only, because the facade is the
 * reference assembly path; the direct side is exercised through its own discovery and injection entry
 * points, because those are the ones a hand written caller would use.
 */
@DisplayName("FacadeBenchmark 装配路径单元测试")
class FacadePathAssemblyUnitTest {

    /** Name the facade strategy prefers, and the direct path repeats. */
    private static final String DEFAULT_PROVIDER_NAME = "default-reflection";

    /** Service file name the SPI discovery reads; hiding it simulates a deployment without providers. */
    private static final String SPI_SERVICE_FILE =
            "META-INF/services/com.nona.changeTracking.spi.TrackingCapabilityProvider";

    @Test
    @DisplayName("直连路径应经真实 SPI 发现 default-reflection 提供者")
    void discoverProviders_shouldFindTheDefaultReflectionProviderThroughTheRealSpi() {
        final Map<String, TrackingCapabilityProvider> providers = DirectPath.discoverProviders();

        assertThat(providers).containsKey(DEFAULT_PROVIDER_NAME);
        assertThat(providers.get(DEFAULT_PROVIDER_NAME).getName()).isEqualTo(DEFAULT_PROVIDER_NAME);
    }

    @Test
    @DisplayName("注入多个提供者时应优先选中 default-reflection，且不创建其他提供者")
    void assembleWithInjectedProviders_shouldPreferTheDefaultReflectionProvider() {
        final StubProvider preferred = new StubProvider(DEFAULT_PROVIDER_NAME);
        final StubProvider other = new StubProvider("aaa-other");

        final ChangeTracker tracker = DirectPath.assemble(
                Map.of("aaa-other", other, DEFAULT_PROVIDER_NAME, preferred));

        assertThat(tracker).isNotNull();
        assertThat(preferred.createCalls()).isEqualTo(1);
        assertThat(other.createCalls()).isZero();
    }

    @Test
    @DisplayName("无 default-reflection 时应回退到名称排序第一个提供者")
    void assembleWithInjectedProviders_shouldFallBackToTheAlphabeticallyFirstProvider() {
        final StubProvider zeta = new StubProvider("zeta");
        final StubProvider alpha = new StubProvider("alpha");

        final ChangeTracker tracker = DirectPath.assemble(Map.of("zeta", zeta, "alpha", alpha));

        assertThat(tracker).isNotNull();
        assertThat(alpha.createCalls()).isEqualTo(1);
        assertThat(zeta.createCalls()).isZero();
    }

    @Test
    @DisplayName("空提供者集合应被直连装配拒绝，且不产生追踪器")
    void assembleWithInjectedProviders_shouldRejectAnEmptyProviderSet() {
        assertThatThrownBy(() -> DirectPath.assemble(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No TrackingCapabilityProviders");
    }

    @Test
    @DisplayName("空参数注入入口应拒绝 null 提供者集合")
    void assembleWithInjectedProviders_shouldRejectANullProviderSet() {
        final Map<String, TrackingCapabilityProvider> providers = null;

        assertThatThrownBy(() -> DirectPath.assemble(providers))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("提供者创建失败应向调用方传播，不被吞掉也不被包装")
    void assembleWithInjectedProviders_shouldPropagateProviderCreationFailures() {
        final StubProvider failing = new StubProvider(DEFAULT_PROVIDER_NAME,
                new IllegalStateException("capability creation failed"));

        assertThatThrownBy(() -> DirectPath.assemble(Map.of(DEFAULT_PROVIDER_NAME, failing)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capability creation failed");
    }

    @Test
    @DisplayName("门面装配与直连装配应在同一输入形态上产出行为等价的追踪器")
    void facadeAndDirectAssembly_shouldProduceEquivalentTrackers() {
        final Object facadeSample = SampleFamily.create(SampleShape.defaults());
        final Object directSample = SampleFamily.create(SampleShape.defaults());

        final ChangeTracker facadeTracker = FacadePath.assemble();
        final ChangeTracker directTracker = DirectPath.assemble();

        facadeTracker.track(facadeSample);
        directTracker.track(directSample);
        SampleMutator.changeField(facadeSample, "status");
        SampleMutator.changeField(directSample, "status");

        assertThat(directTracker.calculateChangesFor(directSample).getLeafChanges())
                .as("direct changes of one field on the default shape")
                .hasSameSizeAs(facadeTracker.calculateChangesFor(facadeSample).getLeafChanges())
                .isNotEmpty();
    }

    @Test
    @DisplayName("无可发现提供者时两侧应以同一失败语义拒绝装配")
    void bothPaths_shouldRejectEquivalentlyWhenNoProviderIsDiscoverable() {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ProviderHidingClassLoader(original));
        try {
            assertThatThrownBy(() -> ChangeTrackerFactory.builder().withDefaults().build())
                    .as("facade assembly without a discoverable provider")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No TrackingCapabilityProviders");
            assertThatThrownBy(DirectPath::assemble)
                    .as("direct assembly without a discoverable provider")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * Test provider that counts its creation calls and can be made to fail; it never applies
     * configuration, which keeps both assembly paths on the unconfigured comparison.
     */
    private static final class StubProvider implements TrackingCapabilityProvider {

        /** Name this provider reports and is selected by. */
        private final String name;

        /** Failure thrown by {@link #create()}, null for a working provider. */
        private final RuntimeException failure;

        /** Number of {@link #create()} calls seen so far. */
        private int createCalls;

        /**
         * Creates a working stub provider on the given name.
         *
         * @param name the provider name
         */
        StubProvider(final String name) {
            this(name, null);
        }

        /**
         * Creates a stub provider that fails on creation when a failure is given.
         *
         * @param name    the provider name
         * @param failure the failure thrown by {@code create()}, null for a working provider
         */
        StubProvider(final String name, final RuntimeException failure) {
            this.name = name;
            this.failure = failure;
        }

        /**
         * Returns the number of creation calls seen so far.
         *
         * @return the creation call count
         */
        int createCalls() {
            return createCalls;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getName() {
            return name;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public <T> TrackingCapabilityProvider withIdentifier(final Class<T> type,
                                                            final Function<T, Object> extractor) {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TrackingCapabilityProvider withValueType(final Class<?> type) {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TrackingCapabilityProvider withValuePackage(final String packageName) {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public TrackingCapability<?> create() {
            createCalls++;
            if (failure != null) {
                throw failure;
            }
            return realCapability();
        }

        /**
         * Returns a capability created by the provider discovered through the real SPI, so an
         * assembled tracker stays usable and no test local capability implementation is needed.
         *
         * @return a capability of the discovered default provider
         */
        private static TrackingCapability<?> realCapability() {
            return DirectPath.discoverProviders().get(DEFAULT_PROVIDER_NAME).create();
        }
    }

    /**
     * Class loader hiding the SPI service file, used to make provider discovery fail the way a
     * deployment without the core provider fails; every other resource stays visible to the parent.
     */
    private static final class ProviderHidingClassLoader extends ClassLoader {

        /**
         * Creates the hiding loader on top of the given parent.
         *
         * @param parent the loader that would otherwise expose the SPI service file
         */
        ProviderHidingClassLoader(final ClassLoader parent) {
            super(parent);
        }

        /**
         * Hides the SPI service file from the single resource lookup.
         *
         * @param name the resource name
         * @return null for the SPI service file, the parent result otherwise
         */
        @Override
        public URL getResource(final String name) {
            if (SPI_SERVICE_FILE.equals(name)) {
                return null;
            }
            return super.getResource(name);
        }

        /**
         * Hides the SPI service file from the enumeration lookup used by the service loader.
         *
         * @param name the resource name
         * @return an empty enumeration for the SPI service file, the parent result otherwise
         * @throws IOException if the parent lookup fails
         */
        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            if (SPI_SERVICE_FILE.equals(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }
}
