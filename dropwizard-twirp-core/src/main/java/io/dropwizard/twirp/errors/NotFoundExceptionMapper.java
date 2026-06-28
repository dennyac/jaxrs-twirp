package io.dropwizard.twirp.errors;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a JAX-RS {@link NotFoundException} (no resource matched the request URL)
 * to a Twirp {@code bad_route} JSON error so unknown routes return a parseable
 * Twirp envelope instead of the container's HTML 404.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException exception) {
        return BadRouteResponses.badRoute("no Twirp handler for the requested URL");
    }
}
