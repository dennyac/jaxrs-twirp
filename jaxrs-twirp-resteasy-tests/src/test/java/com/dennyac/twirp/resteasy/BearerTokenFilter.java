// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.resteasy;

import com.dennyac.twirp.ErrorCode;
import com.dennyac.twirp.TwirpException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/** Accepts {@code Authorization: Bearer hat-token} as the user {@code alice}. */
final class BearerTokenFilter implements ContainerRequestFilter {

    static final String AUTHORIZATION = "Bearer hat-token";

    @Override
    public void filter(ContainerRequestContext request) {
        if (!AUTHORIZATION.equals(request.getHeaderString(HttpHeaders.AUTHORIZATION))) {
            throw new TwirpException(ErrorCode.UNAUTHENTICATED, "missing or invalid bearer token");
        }
        boolean secure = request.getSecurityContext().isSecure();
        request.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return () -> "alice";
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }

            @Override
            public boolean isSecure() {
                return secure;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        });
    }
}
