package io.dropwizard.twirp.errors;

import io.dropwizard.twirp.ErrorCode;
import io.dropwizard.twirp.TwirpError;
import io.dropwizard.twirp.TwirpMediaTypes;
import jakarta.ws.rs.core.Response;

/**
 * Shared helper for the routing-failure mappers. Twirp v7 requires that requests
 * which cannot be routed to a service method — wrong URL, wrong HTTP method, or an
 * unsupported request content type — come back as a {@code bad_route} JSON error
 * (HTTP 404), not the JAX-RS implementation's default HTML 404/405/415.
 */
final class BadRouteResponses {

    private BadRouteResponses() {
        // utility class
    }

    static Response badRoute(String message) {
        return Response.status(ErrorCode.BAD_ROUTE.httpStatus())
                .type(TwirpMediaTypes.APPLICATION_JSON_TYPE)
                .entity(TwirpError.of(ErrorCode.BAD_ROUTE, message))
                .build();
    }
}
