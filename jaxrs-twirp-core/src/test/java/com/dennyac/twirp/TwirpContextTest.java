// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwirpContextTest {

    @Test
    void emptyContextHasNoHeadersNoPrincipalNoRoles() {
        TwirpContext context = TwirpContext.empty();

        assertThat(context.headers()).isEmpty();
        assertThat(context.header("Authorization")).isEmpty();
        assertThat(context.headerValues("Authorization")).isEmpty();
        assertThat(context.principal()).isEmpty();
        assertThat(context.isUserInRole("admin")).isFalse();
        assertThat(context.securityContext()).isEmpty();
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        TwirpContext context = TwirpContext.ofOutboundHeaders(Map.of(
                "Authorization", List.of("******"),
                "X-Request-Id", List.of("abc123")));

        assertThat(context.header("authorization")).hasValue("******");
        assertThat(context.header("AUTHORIZATION")).hasValue("******");
        assertThat(context.header("x-request-id")).hasValue("abc123");
        assertThat(context.header("missing")).isEmpty();
    }

    @Test
    void headerValuesReturnsAllValuesInOrder() {
        TwirpContext context = TwirpContext.ofOutboundHeaders(Map.of(
                "Accept-Encoding", List.of("gzip", "br")));

        assertThat(context.headerValues("accept-encoding")).containsExactly("gzip", "br");
        assertThat(context.header("accept-encoding")).hasValue("gzip");
    }

    @Test
    void headerSnapshotIsImmutableAndDecoupledFromSource() {
        java.util.Map<String, List<String>> source = new java.util.HashMap<>();
        source.put("X-Trace", new java.util.ArrayList<>(List.of("one")));

        TwirpContext context = TwirpContext.ofOutboundHeaders(source);

        source.get("X-Trace").add("two");
        source.put("X-Added", List.of("nope"));
        assertThat(context.headerValues("X-Trace")).containsExactly("one");
        assertThat(context.header("X-Added")).isEmpty();
        assertThatThrownBy(() -> context.headers().put("X-Evil", List.of("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void principalAndRolesComeFromSecurityContext() {
        SecurityContext security = fakeSecurity(new NamedPrincipal("alice"), Set.of("user", "admin"));
        TwirpContext context = TwirpContext.of(Map.of("Authorization", List.of("******")), security);

        assertThat(context.principal()).map(Principal::getName).hasValue("alice");
        assertThat(context.isUserInRole("admin")).isTrue();
        assertThat(context.isUserInRole("user")).isTrue();
        assertThat(context.isUserInRole("root")).isFalse();
        assertThat(context.securityContext()).containsSame(security);
        assertThat(context.outboundHeaders()).isEmpty();
    }

    @Test
    void unauthenticatedSecurityContextHasNoPrincipal() {
        SecurityContext security = fakeSecurity(null, Set.of());
        TwirpContext context = TwirpContext.of(Map.of(), security);

        assertThat(context.principal()).isEmpty();
        assertThat(context.isUserInRole("admin")).isFalse();
        assertThat(context.securityContext()).containsSame(security);
    }

    @Test
    void nullSafeFactories() {
        assertThat(TwirpContext.of(null, null).headers()).isEmpty();
        assertThat(TwirpContext.ofOutboundHeaders(null).headers()).isEmpty();
        assertThat(TwirpContext.of(null, null).principal()).isEmpty();
    }

    @Test
    void outboundFactoryMarksOnlyExplicitClientHeadersForPropagation() {
        TwirpContext context = TwirpContext.ofOutboundHeaders(Map.of(
                "Authorization", List.of("******"),
                "traceparent", List.of("00-abc-def-01")));

        assertThat(context.outboundHeaders())
                .containsEntry("Authorization", List.of("******"))
                .containsEntry("traceparent", List.of("00-abc-def-01"));
    }

    @Test
    void outboundFactoryRejectsTransportControlledHeaders() {
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("Content-Type", List.of("text/plain"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content-Type");

        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of(" Content-Type ", List.of("text/plain"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content-Type");

        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("Connection", List.of("close"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connection");

        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("\tConnection\t", List.of("close"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connection");
    }

    @Test
    void outboundFactoryTrimsHeaderNamesAndRejectsLineBreaks() {
        TwirpContext context = TwirpContext.ofOutboundHeaders(
                Map.of(" X-Request-Id ", List.of("abc123")));
        assertThat(context.outboundHeaders()).containsEntry("X-Request-Id", List.of("abc123"));
        assertThat(context.header("x-request-id")).hasValue("abc123");

        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Bad\r\nInjected", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Bad\n", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("\rX-Bad", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Trace", List.of("ok\r\nX-Injected: evil"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Trace", List.of("ok\n"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Trace", List.of("ok\r"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outboundFactoryRejectsControlCharsWhitespaceAndDuplicateNames() {
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("\u0000", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("\u0000X-Foo", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Foo\u0000", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X-Foo\u007F", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X Foo", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(
                Map.of("X\tFoo", List.of("val"))))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, List<String>> paddedDuplicates = new java.util.LinkedHashMap<>();
        paddedDuplicates.put("X-Foo", List.of("one"));
        paddedDuplicates.put(" X-Foo ", List.of("two"));
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(paddedDuplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");

        Map<String, List<String>> caseDuplicates = new java.util.LinkedHashMap<>();
        caseDuplicates.put("X-Foo", List.of("one"));
        caseDuplicates.put("x-foo", List.of("two"));
        assertThatThrownBy(() -> TwirpContext.ofOutboundHeaders(caseDuplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static SecurityContext fakeSecurity(Principal principal, Set<String> roles) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        };
    }

    private record NamedPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
