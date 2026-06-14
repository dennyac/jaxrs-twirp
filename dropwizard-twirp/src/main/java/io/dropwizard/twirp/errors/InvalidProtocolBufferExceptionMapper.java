package io.dropwizard.twirp.errors;

import com.google.protobuf.InvalidProtocolBufferException;
import io.dropwizard.twirp.ErrorCode;
import io.dropwizard.twirp.TwirpError;
import io.dropwizard.twirp.TwirpMediaTypes;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link InvalidProtocolBufferException}s — thrown by
 * {@link io.dropwizard.twirp.codec.ProtobufMessageBodyReader} when the request
 * body cannot be parsed — to a Twirp {@link ErrorCode#MALFORMED} JSON response
 * (HTTP 400).
 */
@Provider
public class InvalidProtocolBufferExceptionMapper
        implements ExceptionMapper<InvalidProtocolBufferException> {

    @Override
    public Response toResponse(InvalidProtocolBufferException exception) {
        return Response.status(ErrorCode.MALFORMED.httpStatus())
                .type(TwirpMediaTypes.APPLICATION_JSON_TYPE)
                .entity(TwirpError.of(ErrorCode.MALFORMED,
                        "the request payload could not be decoded: " + exception.getMessage()))
                .build();
    }
}
