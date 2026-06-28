package io.dropwizard.twirp;

import io.dropwizard.twirp.codec.ProtobufJsonMessageBodyReader;
import io.dropwizard.twirp.codec.ProtobufJsonMessageBodyWriter;
import io.dropwizard.twirp.codec.ProtobufMessageBodyReader;
import io.dropwizard.twirp.codec.ProtobufMessageBodyWriter;
import io.dropwizard.twirp.errors.InvalidProtocolBufferExceptionMapper;
import io.dropwizard.twirp.errors.MethodNotAllowedExceptionMapper;
import io.dropwizard.twirp.errors.NotFoundExceptionMapper;
import io.dropwizard.twirp.errors.TwirpExceptionMapper;
import io.dropwizard.twirp.errors.UnsupportedMediaTypeExceptionMapper;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TwirpServerFeatureTest {

    @Test
    void registersBodyProvidersAndExceptionMappers() {
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
                        NotFoundExceptionMapper.class,
                        MethodNotAllowedExceptionMapper.class,
                        UnsupportedMediaTypeExceptionMapper.class);
    }

    @Test
    void rejectsNullPrinterOrParser() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TwirpServerFeature(null, TwirpJson.defaultParser()));
        assertThatNullPointerException()
                .isThrownBy(() -> new TwirpServerFeature(TwirpJson.defaultPrinter(), null));
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
