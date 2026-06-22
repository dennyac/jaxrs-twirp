package io.dropwizard.twirp;

import com.google.protobuf.util.JsonFormat;
import io.dropwizard.twirp.codec.ProtobufJsonMessageBodyReader;
import io.dropwizard.twirp.codec.ProtobufJsonMessageBodyWriter;
import io.dropwizard.twirp.codec.ProtobufMessageBodyReader;
import io.dropwizard.twirp.codec.ProtobufMessageBodyWriter;
import io.dropwizard.twirp.errors.InvalidProtocolBufferExceptionMapper;
import io.dropwizard.twirp.errors.TwirpExceptionMapper;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

import java.util.Objects;

/**
 * Framework-agnostic JAX-RS {@link Feature} that registers everything needed to
 * <em>serve</em> Twirp endpoints from any JAX-RS application — Jersey, RESTEasy,
 * or anything else implementing JAX-RS 3.1:
 *
 * <ul>
 *   <li>{@link ProtobufMessageBodyReader} / {@link ProtobufMessageBodyWriter}
 *       for {@code application/protobuf}</li>
 *   <li>{@link ProtobufJsonMessageBodyReader} / {@link ProtobufJsonMessageBodyWriter}
 *       for {@code application/json}</li>
 *   <li>{@link TwirpExceptionMapper} — renders a {@link TwirpException} as the
 *       wire-format JSON error</li>
 *   <li>{@link InvalidProtocolBufferExceptionMapper} — renders malformed
 *       request bytes as a Twirp {@code malformed} 400</li>
 * </ul>
 *
 * <p>Register it on your application's {@code Configurable} (for example a Jersey
 * {@code ResourceConfig}) alongside the generated resource classes:
 * <pre>{@code
 * ResourceConfig config = new ResourceConfig();
 * config.register(new TwirpServerFeature());
 * config.register(new HaberdasherResource(new MyHaberdasher()));
 * }</pre>
 *
 * <p>This class carries <strong>no Dropwizard dependency</strong>, so plain
 * Jakarta JAX-RS apps can serve Twirp with exactly the same runtime. Dropwizard
 * applications don't register it directly — {@link TwirpBundle} installs it for
 * them on the Jersey environment.
 *
 * <p>For client-side provider registration (no exception mappers), see
 * {@link TwirpClients#registerProviders(jakarta.ws.rs.core.Configurable)}.
 *
 * <p>To customize the JSON printer/parser (field-presence semantics, a
 * {@link com.google.protobuf.TypeRegistry} for {@code google.protobuf.Any}, etc.)
 * supply them through the {@linkplain #TwirpServerFeature(JsonFormat.Printer,
 * JsonFormat.Parser) two-arg constructor}.
 */
public final class TwirpServerFeature implements Feature {

    private final JsonFormat.Printer jsonPrinter;
    private final JsonFormat.Parser jsonParser;

    /** Uses the default Twirp JSON printer/parser (see {@link TwirpJson}). */
    public TwirpServerFeature() {
        this(TwirpJson.defaultPrinter(), TwirpJson.defaultParser());
    }

    public TwirpServerFeature(JsonFormat.Printer jsonPrinter, JsonFormat.Parser jsonParser) {
        this.jsonPrinter = Objects.requireNonNull(jsonPrinter, "jsonPrinter");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser");
    }

    @Override
    public boolean configure(FeatureContext context) {
        context.register(new ProtobufMessageBodyReader());
        context.register(new ProtobufMessageBodyWriter());
        context.register(new ProtobufJsonMessageBodyReader(jsonParser));
        context.register(new ProtobufJsonMessageBodyWriter(jsonPrinter));
        context.register(new TwirpExceptionMapper());
        context.register(new InvalidProtocolBufferExceptionMapper());
        return true;
    }
}
