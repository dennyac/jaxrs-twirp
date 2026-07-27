package com.dennyac.twirp;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rewrites JAX-RS routing failures as Twirp {@code bad_route} responses, but
 * only beneath configured Twirp URL prefixes.
 */
final class TwirpBadRouteFilter implements ContainerResponseFilter {

    private static final Set<Integer> ROUTING_FAILURE_STATUSES = Set.of(404, 405, 415);
    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private final List<String> pathPrefixes;

    TwirpBadRouteFilter(List<String> pathPrefixes) {
        this.pathPrefixes = List.copyOf(pathPrefixes);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        int status = response.getStatus();
        if (!ROUTING_FAILURE_STATUSES.contains(status)) {
            return;
        }
        if (!shouldRewrite(request.getUriInfo().getPath(false), response.getEntity())) {
            return;
        }

        String message = switch (status) {
            case 405 -> "Twirp endpoints only accept POST";
            case 415 -> "Twirp requires application/protobuf or application/json";
            default -> "no Twirp handler for the requested URL";
        };

        response.setStatus(ErrorCode.BAD_ROUTE.httpStatus());
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        response.getHeaders().remove("Allow");
        response.setEntity(
                TwirpError.of(ErrorCode.BAD_ROUTE, message),
                NO_ANNOTATIONS,
                TwirpMediaTypes.APPLICATION_JSON_TYPE);
    }

    boolean matchesPath(String requestPath) {
        String path = requestPath == null || requestPath.isEmpty()
                ? "/"
                : requestPath.startsWith("/") ? requestPath : "/" + requestPath;
        for (String prefix : pathPrefixes) {
            if ("/".equals(prefix) || path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    boolean shouldRewrite(String requestPath, Object entity) {
        return matchesPath(requestPath) && !(entity instanceof TwirpError);
    }

    static List<String> normalizePrefixes(String... pathPrefixes) {
        Objects.requireNonNull(pathPrefixes, "pathPrefixes");
        if (pathPrefixes.length == 0) {
            throw new IllegalArgumentException("at least one Twirp path prefix is required");
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String pathPrefix : pathPrefixes) {
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            String prefix = pathPrefix.trim();
            if (prefix.isEmpty() || "/".equals(prefix)) {
                normalized.add("/");
                continue;
            }
            if (!prefix.startsWith("/")) {
                prefix = "/" + prefix;
            }
            while (prefix.length() > 1 && prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            normalized.add(prefix);
        }
        return new ArrayList<>(normalized);
    }
}
