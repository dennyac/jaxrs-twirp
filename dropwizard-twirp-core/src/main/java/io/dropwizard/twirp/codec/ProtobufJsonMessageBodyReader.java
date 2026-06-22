package io.dropwizard.twirp.codec;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.dropwizard.twirp.TwirpJson;
import io.dropwizard.twirp.TwirpMediaTypes;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Jersey {@link MessageBodyReader} for Twirp {@code application/json} request
 * bodies, backed by protobuf's canonical JSON mapping.
 *
 * <p>Unknown fields are ignored (matching the Twirp Go server's
 * {@code DiscardUnknown: true} behavior).
 */
@Provider
@Consumes(TwirpMediaTypes.APPLICATION_JSON)
public class ProtobufJsonMessageBodyReader implements MessageBodyReader<Message> {

    private final JsonFormat.Parser parser;

    public ProtobufJsonMessageBodyReader() {
        this(TwirpJson.defaultParser());
    }

    public ProtobufJsonMessageBodyReader(JsonFormat.Parser parser) {
        this.parser = parser;
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations,
                              MediaType mediaType) {
        return Message.class.isAssignableFrom(type)
                && TwirpMediaTypes.APPLICATION_JSON_TYPE.isCompatible(mediaType);
    }

    @Override
    public Message readFrom(Class<Message> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                            InputStream entityStream) throws IOException, WebApplicationException {
        Message prototype = ProtobufMessageBodyReader.defaultInstanceOf(type);
        Message.Builder builder = prototype.newBuilderForType();
        try (InputStreamReader reader = new InputStreamReader(entityStream, StandardCharsets.UTF_8)) {
            parser.merge(reader, builder);
        }
        return builder.build();
    }
}
