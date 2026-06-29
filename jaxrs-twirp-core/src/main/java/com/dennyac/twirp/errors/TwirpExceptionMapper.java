package com.dennyac.twirp.errors;

import com.dennyac.twirp.TwirpError;
import com.dennyac.twirp.TwirpException;
import com.dennyac.twirp.TwirpMediaTypes;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a thrown {@link TwirpException} to a Twirp-formatted JSON error
 * response.
 *
 * <p>The HTTP status is taken from {@link com.dennyac.twirp.ErrorCode#httpStatus()}
 * and the body is always {@code application/json} regardless of the request's
 * Content-Type (per the Twirp spec).
 */
@Provider
public class TwirpExceptionMapper implements ExceptionMapper<TwirpException> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TwirpExceptionMapper.class);

    @Override
    public Response toResponse(TwirpException exception) {
        int status = exception.getErrorCode().httpStatus();
        if (status >= 500 && exception.getCause() != null) {
            LOGGER.warn("Twirp {} error: {}",
                    exception.getErrorCode().wireValue(), exception.getMessage(),
                    exception.getCause());
        }
        return Response.status(status)
                .type(TwirpMediaTypes.APPLICATION_JSON_TYPE)
                .entity(TwirpError.of(exception))
                .build();
    }
}
