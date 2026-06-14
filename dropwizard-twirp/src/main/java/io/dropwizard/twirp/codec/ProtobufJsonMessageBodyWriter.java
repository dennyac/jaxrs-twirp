package io.dropwizard.twirp.codec;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.dropwizard.twirp.TwirpMediaTypes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Jersey {@link MessageBodyWriter} for Twirp {@code application/json} response
 * bodies, backed by protobuf's canonical JSON mapping.
 *
 * <p>By default the printer is configured to match the Twirp Go server's default
 * behavior:
 * <ul>
 *   <li>{@code preservingProtoFieldNames()} — emit snake_case proto field names
 *       rather than camelCase.</li>
 *   <li>{@code includingDefaultValueFields()} — emit zero-value fields, matching
 *       {@code EmitUnpopulated: true}.</li>
 *   <li>{@code omittingInsignificantWhitespace()} — produce compact JSON.</li>
 * </ul>
 */
@Provider
@Produces(TwirpMediaTypes.APPLICATION_JSON)
public class ProtobufJsonMessageBodyWriter implements MessageBodyWriter<Message> {

    private final JsonFormat.Printer printer;

    public ProtobufJsonMessageBodyWriter() {
        this(JsonFormat.printer()
                .preservingProtoFieldNames()
                .includingDefaultValueFields()
                .omittingInsignificantWhitespace());
    }

    public ProtobufJsonMessageBodyWriter(JsonFormat.Printer printer) {
        this.printer = printer;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations,
                               MediaType mediaType) {
        return Message.class.isAssignableFrom(type)
                && TwirpMediaTypes.APPLICATION_JSON_TYPE.isCompatible(mediaType);
    }

    @Override
    public void writeTo(Message message, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Writer writer = new OutputStreamWriter(entityStream, StandardCharsets.UTF_8);
        printer.appendTo(message, writer);
        writer.flush();
    }
}
