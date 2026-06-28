package io.dropwizard.twirp.errors;

import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a JAX-RS {@link NotSupportedException} (request {@code Content-Type} is
 * neither {@code application/protobuf} nor {@code application/json}) to a Twirp
 * {@code bad_route} JSON error instead of a 415.
 */
@Provider
public class UnsupportedMediaTypeExceptionMapper implements ExceptionMapper<NotSupportedException> {

    @Override
    public Response toResponse(NotSupportedException exception) {
        return BadRouteResponses.badRoute(
                "Twirp requires application/protobuf or application/json");
    }
}
