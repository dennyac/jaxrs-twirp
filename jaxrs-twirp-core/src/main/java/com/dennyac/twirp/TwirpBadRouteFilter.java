// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

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

/**
 * Rewrites JAX-RS routing failures as Twirp {@code bad_route} responses, and
 * server errors (HTTP 5xx) that are not already Twirp errors as
 * {@code unimplemented}, {@code unavailable}, or {@code internal}, but only
 * beneath configured Twirp URL prefixes. Rewritten server errors carry a
 * generic message instead of the original body.
 */
final class TwirpBadRouteFilter implements ContainerResponseFilter {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private final List<String> pathPrefixes;

    TwirpBadRouteFilter(List<String> pathPrefixes) {
        this.pathPrefixes = List.copyOf(pathPrefixes);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Rewrite rewrite = rewriteFor(response.getStatus());
        if (rewrite == null) {
            return;
        }
        if (!shouldRewrite(request.getUriInfo().getPath(false), response.getEntity())) {
            return;
        }

        response.setStatus(rewrite.code().httpStatus());
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        response.getHeaders().remove("Allow");
        response.setEntity(
                TwirpError.of(rewrite.code(), rewrite.message()),
                NO_ANNOTATIONS,
                TwirpMediaTypes.APPLICATION_JSON_TYPE);
    }

    private static Rewrite rewriteFor(int status) {
        return switch (status) {
            case 404 -> new Rewrite(ErrorCode.BAD_ROUTE, "no Twirp handler for the requested URL");
            case 405 -> new Rewrite(ErrorCode.BAD_ROUTE, "Twirp endpoints only accept POST");
            case 415 -> new Rewrite(ErrorCode.BAD_ROUTE,
                    "Twirp requires application/protobuf or application/json");
            case 501 -> new Rewrite(ErrorCode.UNIMPLEMENTED, "the requested method is not implemented");
            case 503 -> new Rewrite(ErrorCode.UNAVAILABLE, "the service is unavailable");
            default -> status >= 500 ? new Rewrite(ErrorCode.INTERNAL, "internal server error") : null;
        };
    }

    private record Rewrite(ErrorCode code, String message) {
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
