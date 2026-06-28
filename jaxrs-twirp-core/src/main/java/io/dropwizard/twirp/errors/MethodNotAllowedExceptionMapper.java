package io.dropwizard.twirp.errors;

import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps a JAX-RS {@link NotAllowedException} (a Twirp endpoint hit with something
 * other than {@code POST}) to a Twirp {@code bad_route} JSON error. Twirp speaks
 * POST only; anything else is an unroutable request, not a 405.
 */
@Provider
public class MethodNotAllowedExceptionMapper implements ExceptionMapper<NotAllowedException> {

    @Override
    public Response toResponse(NotAllowedException exception) {
        return BadRouteResponses.badRoute("Twirp endpoints only accept POST");
    }
}
