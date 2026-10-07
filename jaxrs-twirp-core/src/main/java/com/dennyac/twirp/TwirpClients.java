// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.google.protobuf.util.JsonFormat;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufJsonMessageBodyWriter;
import com.dennyac.twirp.codec.ProtobufMessageBodyReader;
import com.dennyac.twirp.codec.ProtobufMessageBodyWriter;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client-side companion to {@link TwirpServerFeature}. Provides helpers
 * for generated JAX-RS clients to talk Twirp:
 *
 * <ul>
 *   <li>{@link #registerProviders(Configurable)} attaches the
 *       protobuf and protobuf-JSON {@code MessageBodyReader}/{@code Writer}
 *       providers to a JAX-RS {@link Configurable} (a {@code Client},
 *       {@code WebTarget}, or {@code Invocation.Builder}).
 *   <li>{@link #invoke(Invocation.Builder, Entity, Class)} sends a Twirp
 *       request and either returns the decoded protobuf response, or — on a
 *       non-2xx response — throws the {@link TwirpException} built by
 *       {@link #decodeError(Response)}.
 * </ul>
 *
 * <p>Generated client classes call these helpers; user code typically does
 * not, except to construct a configured {@link jakarta.ws.rs.client.WebTarget}.
 *
 * <p>Example wiring with a JAX-RS {@code ClientBuilder}:
 * <pre>{@code
 * Client client = ClientBuilder.newClient();
 * WebTarget base = client.target("http://hat-service.local:8080");
 * Haberdasher remote = new HaberdasherClient(base);
 * Hat hat = remote.makeHat(Size.newBuilder().setInches(12).build());
 * }</pre>
 */
public final class TwirpClients {

    // Rejects unknown fields and non-string values, like the Go client's decoder.
    private static final ObjectMapper ERROR_MAPPER = JsonMapper.builder()
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

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
     * Convert a non-2xx response into a {@link TwirpException}, the way the
     * Twirp Go client does.
     *
     * <ul>
     *   <li>A 3xx response becomes {@link ErrorCode#INTERNAL}, with its
     *       {@code Location} header in the {@code location} metadata entry.
     *       The body is not read.
     *   <li>A Twirp error body keeps the server's code, message and metadata.
     *       A {@code code} that isn't a Twirp error code becomes
     *       {@link ErrorCode#INTERNAL}, with the raw body in {@code body}.
     *   <li>Any other body is treated as an error from an intermediary such as
     *       a proxy or load balancer. The error code comes from the HTTP status,
     *       for example 503 becomes {@link ErrorCode#UNAVAILABLE}, and the raw
     *       body goes in {@code body}.
     * </ul>
     *
     * <p>Errors built from the HTTP status, including those for 3xx responses,
     * also carry {@code http_error_from_intermediary=true} and
     * {@code status_code} metadata. The Content-Type header is ignored because
     * intermediaries may replace it. A body that can't be read becomes
     * {@link ErrorCode#INTERNAL}.
     */
    public static TwirpException decodeError(Response response) {
        Objects.requireNonNull(response, "response");
        int status = response.getStatus();
        if (isRedirect(status)) {
            return redirectError(status, response.getHeaderString(HttpHeaders.LOCATION));
        }
        String body;
        try {
            body = response.readEntity(String.class);
        } catch (ProcessingException e) {
            return new TwirpException(ErrorCode.INTERNAL,
                    "failed to read server error response body: " + rootMessage(e), e);
        }
        return decodeErrorBody(status, body);
    }

    /**
     * Build the {@link TwirpException} for an error response from its HTTP
     * status and raw body, using the rules of {@link #decodeError(Response)}.
     * A 3xx status is reported with an empty {@code location}.
     */
    public static TwirpException decodeErrorBody(int status, String body) {
        if (isRedirect(status)) {
            return redirectError(status, null);
        }
        String rawBody = body == null ? "" : body;
        TwirpError error = parseTwirpError(rawBody);
        if (error == null || error.getCode() == null || error.getCode().isEmpty()) {
            return intermediaryError(status,
                    "Error from intermediary with HTTP status code " + status
                            + " \"" + statusText(status) + "\"",
                    rawBody);
        }
        ErrorCode code = knownErrorCode(error.getCode());
        if (code == null) {
            return TwirpException.builder(ErrorCode.INTERNAL)
                    .message("invalid type returned from server error response: " + error.getCode())
                    .meta("body", rawBody)
                    .build();
        }
        TwirpException.Builder builder = TwirpException.builder(code).message(error.getMsg());
        error.getMeta().forEach((key, value) -> builder.meta(key, value == null ? "" : value));
        return builder.build();
    }

    private static TwirpError parseTwirpError(String body) {
        try {
            return ERROR_MAPPER.readValue(body, TwirpError.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static TwirpException redirectError(int status, String location) {
        String target = location == null ? "" : location;
        return intermediaryError(status,
                "unexpected HTTP status code " + status + " \"" + statusText(status)
                        + "\" received, Location=\"" + target + "\"",
                target);
    }

    private static TwirpException intermediaryError(int status, String message, String bodyOrLocation) {
        return TwirpException.builder(intermediaryErrorCode(status))
                .message(message)
                .meta("http_error_from_intermediary", "true")
                .meta("status_code", Integer.toString(status))
                .meta(isRedirect(status) ? "location" : "body", bodyOrLocation)
                .build();
    }

    private static ErrorCode intermediaryErrorCode(int status) {
        if (isRedirect(status)) {
            return ErrorCode.INTERNAL;
        }
        return switch (status) {
            case 400 -> ErrorCode.INTERNAL;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.PERMISSION_DENIED;
            case 404 -> ErrorCode.BAD_ROUTE;
            case 429 -> ErrorCode.RESOURCE_EXHAUSTED;
            case 502, 503, 504 -> ErrorCode.UNAVAILABLE;
            default -> ErrorCode.UNKNOWN;
        };
    }

    private static ErrorCode knownErrorCode(String wireValue) {
        for (ErrorCode code : ErrorCode.values()) {
            if (code.wireValue().equals(wireValue)) {
                return code;
            }
        }
        return null;
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status <= 399;
    }

    private static String statusText(int status) {
        Response.Status known = Response.Status.fromStatusCode(status);
        return known == null ? "" : known.getReasonPhrase();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
