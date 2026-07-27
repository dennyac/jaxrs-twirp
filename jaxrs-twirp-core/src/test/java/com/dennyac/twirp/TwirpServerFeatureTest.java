package com.dennyac.twirp;

import com.dennyac.twirp.codec.ProtobufJsonMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyWriter;
import com.dennyac.twirp.codec.ProtobufMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufMessageBodyWriter;
import com.dennyac.twirp.errors.InvalidProtocolBufferExceptionMapper;
import com.dennyac.twirp.errors.TwirpExceptionMapper;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TwirpServerFeatureTest {

    @Test
    void registersBodyProvidersExceptionMappersAndRouteFilter() {
        RecordingFeatureContext context = new RecordingFeatureContext();

        boolean enabled = new TwirpServerFeature().configure(context);

        assertThat(enabled).isTrue();
        assertThat(context.registered)
                .extracting(Object::getClass)
                .containsExactlyInAnyOrder(
                        ProtobufMessageBodyReader.class,
                        ProtobufMessageBodyWriter.class,
                        ProtobufJsonMessageBodyReader.class,
                        ProtobufJsonMessageBodyWriter.class,
                        TwirpExceptionMapper.class,
                        InvalidProtocolBufferExceptionMapper.class,
                        TwirpBadRouteFilter.class);
    }

    @Test
    void rejectsNullPrinterOrParser() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TwirpServerFeature(null, TwirpJson.defaultParser()));
        assertThatNullPointerException()
                .isThrownBy(() -> new TwirpServerFeature(TwirpJson.defaultPrinter(), null));
    }

    @Test
    void routeFilterUsesConfiguredPrefixesWithoutPartialSegmentMatches() {
        RecordingFeatureContext context = new RecordingFeatureContext();
        new TwirpServerFeature("/rpc/", "internal/twirp").configure(context);

        TwirpBadRouteFilter filter = context.registered.stream()
                .filter(TwirpBadRouteFilter.class::isInstance)
                .map(TwirpBadRouteFilter.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(filter.matchesPath("/rpc/example.Service/Call")).isTrue();
        assertThat(filter.matchesPath("/internal/twirp/example.Service/Call")).isTrue();
        assertThat(filter.matchesPath("/rpcish/example.Service/Call")).isFalse();
        assertThat(filter.matchesPath("/api/users")).isFalse();
        assertThat(filter.shouldRewrite("/rpc/example.Service/Call", null)).isTrue();
        assertThat(filter.shouldRewrite(
                "/rpc/example.Service/Call",
                TwirpError.of(ErrorCode.NOT_FOUND, "missing"))).isFalse();
    }

    @Test
    void rejectsMissingPathPrefixes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TwirpServerFeature(new String[0]))
                .withMessageContaining("at least one");
    }

    /** Minimal {@link FeatureContext} that records {@code register(...)} calls. */
    private static final class RecordingFeatureContext implements FeatureContext {
        final List<Object> registered = new ArrayList<>();

        @Override
        public FeatureContext register(Object component) {
            registered.add(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            registered.add(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            registered.add(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            registered.add(component);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            registered.add(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            registered.add(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            registered.add(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            registered.add(componentClass);
            return this;
        }

        @Override
        public FeatureContext property(String name, Object value) {
            return this;
        }

        @Override
        public Configuration getConfiguration() {
            return null;
        }
    }
}
