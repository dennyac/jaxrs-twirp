package com.dennyac.twirp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyWriter;
import com.dennyac.twirp.codec.ProtobufMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufMessageBodyWriter;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side companion to the server-side {@code TwirpBundle}. Provides helpers
 * for generated Jersey clients to talk Twirp:
 *
 * <ul>
 *   <li>{@link #registerProviders(Configurable)} attaches the
 *       protobuf and protobuf-JSON {@code MessageBodyReader}/{@code Writer}
 *       providers to a JAX-RS {@link Configurable} (a {@code Client},
 *       {@code WebTarget}, or {@code Invocation.Builder}).
 *   <li>{@link #invoke(Invocation.Builder, Entity, Class)} sends a Twirp
 *       request and either returns the decoded protobuf response, or — on a
 *       non-2xx response — decodes the Twirp error envelope and throws a
 *       {@link TwirpException}.
 * </ul>
 *
 * <p>Generated client classes call these helpers; user code typically does
 * not, except to construct a configured {@link jakarta.ws.rs.client.WebTarget}.
 *
 * <p>Example wiring with Dropwizard's {@code JerseyClientBuilder}:
 * <pre>{@code
 * Client client = new JerseyClientBuilder(env)
 *         .using(config.getJerseyClient())
 *         .build("haberdasher");
 * WebTarget base = client.target("http://hat-service.local:8080");
 * Haberdasher remote = new HaberdasherClient(base);
 * Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
 * }</pre>
 */
public final class TwirpClients {

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private TwirpClients() {
        // utility class
    }

    /**
     * Register the standard Twirp body providers on a JAX-RS {@link Configurable}
     * (typically a {@link jakarta.ws.rs.client.Client} or
     * {@link jakarta.ws.rs.client.WebTarget}).
     *
     * <p>Equivalent to calling {@code registerProviders(target, defaultPrinter(),
     * defaultParser())}.
     *
     * <p>JAX-RS provider registration is idempotent: calling this multiple times
     * with the same target is safe but redundant.
     */
    public static void registerProviders(Configurable<?> target) {
        registerProviders(target, TwirpJson.defaultPrinter(), TwirpJson.defaultParser());
    }

    /**
     * Copy the explicitly outbound headers carried by a {@link TwirpContext} onto an outbound
     * {@link Invocation.Builder}, returning the builder to chain.
     *
     * <p>Generated clients call this when code generation is run with the
     * {@code context} option, so a caller can propagate headers such as an
     * authorization token or request ID to the remote service. This is the
     * client-side mirror of the server populating a {@code TwirpContext} from
     * the inbound request, analogous to Go's
     * {@code twirp.WithHTTPRequestHeaders}.
     *
     * <p>Contexts created from an inbound server request do not propagate their
     * headers. Callers must use {@link TwirpContext#ofOutboundHeaders(Map)} to
     * select metadata deliberately. Transport-controlled headers are rejected
     * by that factory. A {@code null} context is a no-op.
     */
    public static Invocation.Builder applyHeaders(Invocation.Builder request, TwirpContext context) {
        Objects.requireNonNull(request, "request");
        if (context == null) {
            return request;
        }
        for (Map.Entry<String, List<String>> entry : context.outboundHeaders().entrySet()) {
            for (String value : entry.getValue()) {
                request = request.header(entry.getKey(), value);
            }
        }
        return request;
    }

    /**
     * Register the standard Twirp body providers with a caller-supplied JSON
     * printer and parser configuration.
     */
    public static void registerProviders(Configurable<?> target,
                                         JsonFormat.Printer printer,
                                         JsonFormat.Parser parser) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(printer, "printer");
        Objects.requireNonNull(parser, "parser");
        target.register(new ProtobufMessageBodyReader());
        target.register(new ProtobufMessageBodyWriter());
        target.register(new ProtobufJsonMessageBodyReader(parser));
        target.register(new ProtobufJsonMessageBodyWriter(printer));
    }

    /**
     * Send a Twirp request and either return the decoded response or throw a
     * {@link TwirpException} carrying the server's error.
     *
     * <p>Any {@link ProcessingException} thrown by the JAX-RS client (transport
     * errors, marshalling errors) is wrapped as
     * {@link ErrorCode#UNAVAILABLE}. Successful (2xx) responses are decoded
     * with the response-side {@code MessageBodyReader} that matches the
     * response Content-Type — both {@code application/protobuf} and
     * {@code application/json} are supported.
     *
     * <p>The response is always closed before returning.
     *
     * @param invocation   the {@code Invocation.Builder} to {@code POST} against
     * @param entity       the request entity (a protobuf {@code Message} wrapped
     *                     in {@link Entity#entity(Object, String)})
     * @param responseType the expected response message class
     * @param <Res>        the expected response message type
     * @return the decoded response message
     * @throws TwirpException if the server returned a non-2xx response, the
     *         response body could not be decoded, or the transport failed
     */
    public static <Res> Res invoke(Invocation.Builder invocation,
                                   Entity<?> entity,
                                   Class<Res> responseType) throws TwirpException {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(responseType, "responseType");

        Response response;
        try {
            response = invocation.post(entity);
        } catch (ProcessingException e) {
            throw new TwirpException(ErrorCode.UNAVAILABLE,
                    "twirp transport failure: " + rootMessage(e), e);
        }

        try {
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                try {
                    return response.readEntity(responseType);
                } catch (ProcessingException e) {
                    throw new TwirpException(ErrorCode.MALFORMED,
                            "twirp response could not be decoded: " + rootMessage(e), e);
                }
            }
            throw decodeError(response);
        } finally {
            response.close();
        }
    }

    /**
     * Decode a non-2xx response body as a Twirp error envelope.
     *
     * <p>Per the Twirp v7 spec the body is always {@code application/json}
     * with a {@code {"code", "msg", "meta"}} shape — but we don't rely on the
     * response Content-Type header being correctly set, since intermediaries
     * (load balancers, proxies) may strip or replace it on error paths.
     *
     * <p>If the body cannot be parsed as a Twirp error, an
     * {@link ErrorCode#UNKNOWN} {@link TwirpException} is returned whose
     * message includes the HTTP status and a snippet of the body for
     * troubleshooting.
     */
    public static TwirpException decodeError(Response response) {
        Objects.requireNonNull(response, "response");
        int status = response.getStatus();
        String body;
        try {
            body = response.readEntity(String.class);
        } catch (ProcessingException e) {
            return new TwirpException(ErrorCode.UNKNOWN,
                    "twirp error response could not be read (HTTP " + status + ")", e);
        }
        return decodeErrorBody(status, body);
    }

    /**
     * Parse a Twirp error envelope from a raw response body string. Exposed
     * separately from {@link #decodeError(Response)} so unit tests can exercise
     * the parsing logic without round-tripping a real HTTP request.
     */
    public static TwirpException decodeErrorBody(int status, String body) {
        TwirpError error = null;
        if (body != null && !body.isEmpty()) {
            try {
                error = ERROR_MAPPER.readValue(body, TwirpError.class);
            } catch (IOException e) {
                // fall through to fallback below
            }
        }
        if (error == null || error.getCode() == null) {
            String snippet = body == null ? "" : body.length() > 256 ? body.substring(0, 256) + "..." : body;
            return new TwirpException(ErrorCode.UNKNOWN,
                    "non-Twirp error response (HTTP " + status + "): " + snippet);
        }

        ErrorCode code = ErrorCode.fromWireValue(error.getCode());
        String message = error.getMsg() == null ? error.getCode() : error.getMsg();
        TwirpException.Builder builder = TwirpException.builder(code).message(message);
        for (var entry : error.getMeta().entrySet()) {
            builder.meta(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /**
     * Returns a media-type sanity check used by tests: confirm the response
     * carries an {@code application/json} content-type as required by the spec.
     */
    static boolean isJsonContent(MediaType type) {
        return type != null
                && "application".equalsIgnoreCase(type.getType())
                && "json".equalsIgnoreCase(type.getSubtype());
    }

    /** Empty meta map — convenience for tests that don't care about meta entries. */
    static java.util.Map<String, String> noMeta() {
        return Collections.emptyMap();
    }
}
