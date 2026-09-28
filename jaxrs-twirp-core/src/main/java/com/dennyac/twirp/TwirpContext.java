// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-call context handed to a generated Twirp service method when code
 * generation is run with the {@code context} option.
 *
 * <p>It carries the two things a service implementation most often needs from
 * the HTTP layer without leaking the servlet request itself:
 *
 * <ul>
 *   <li>the inbound request <b>headers</b> (case-insensitive), and</li>
 *   <li>the JAX-RS {@link SecurityContext} — hence the authenticated
 *       {@link #principal()} and {@link #isUserInRole(String) role} checks.</li>
 * </ul>
 *
 * <p><b>Where it comes from.</b> On the server the generated JAX-RS resource
 * builds one with {@link #from(HttpHeaders, SecurityContext)} from its
 * {@code @Context}-injected {@code HttpHeaders} and {@code SecurityContext}, so
 * the implementation never sees {@code HttpServletRequest}. On the client the
 * caller constructs one with {@link #ofOutboundHeaders(Map)} (or
 * {@link #empty()}) and the generated client copies only that explicit outbound
 * header set onto the request. Inbound headers are never forwarded implicitly.
 *
 * <p><b>Relationship to Twirp in other languages.</b> This mirrors the
 * <em>principle</em> behind Go's {@code context.Context} first argument and
 * Ruby's {@code env} hash — a per-call value object populated by the transport
 * layer and passed to the handler, never the raw request — but it is a curated
 * HTTP view rather than a literal port of Go's generic key/value bag.
 *
 * <p><b>Immutability.</b> Header values are snapshotted at construction into an
 * unmodifiable, case-insensitive map, so an implementation may safely retain
 * the header data. The wrapped {@link SecurityContext} is held by reference;
 * {@link #principal()}, {@link #isUserInRole(String)} and
 * {@link #securityContext()} are only meaningful for the duration of the
 * request that produced it.
 *
 * <p>This type depends only on the JAX-RS API and {@code java.security}; it is
 * deliberately free of any Dropwizard coupling.
 */
public final class TwirpContext {

    private static final Set<String> TRANSPORT_CONTROLLED_HEADERS = Set.of(
            "accept",
            "connection",
            "content-encoding",
            "content-length",
            "content-type",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> outboundHeaders;
    private final SecurityContext security;

    private TwirpContext(Map<String, List<String>> headers,
                         Map<String, List<String>> outboundHeaders,
                         SecurityContext security) {
        this.headers = headers;
        this.outboundHeaders = outboundHeaders;
        this.security = security;
    }

    /**
     * Build a context from JAX-RS {@code @Context} injectables — the server-side
     * factory used by generated resources.
     *
     * @param headers  the request {@link HttpHeaders}, or {@code null}
     * @param security the request {@link SecurityContext}, or {@code null} when
     *                 no authentication layer is installed
     */
    public static TwirpContext from(HttpHeaders headers, SecurityContext security) {
        Map<String, List<String>> snapshot = caseInsensitive();
        if (headers != null) {
            copyRequestHeaders(snapshot, headers);
        }
        return new TwirpContext(
                Collections.unmodifiableMap(snapshot),
                Collections.emptyMap(),
                security);
    }

    /**
     * Build a context from a plain header map and an optional
     * {@link SecurityContext}. This creates a server-style context: the headers
     * are available to the implementation but are not eligible for outbound
     * propagation. Use {@link #ofOutboundHeaders(Map)} for generated clients.
     *
     * @param headers  header name to values (may be {@code null} or empty)
     * @param security a {@link SecurityContext}, or {@code null}
     */
    public static TwirpContext of(Map<String, List<String>> headers, SecurityContext security) {
        Map<String, List<String>> snapshot = caseInsensitive();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
        }
        return new TwirpContext(
                Collections.unmodifiableMap(snapshot),
                Collections.emptyMap(),
                security);
    }

    /**
     * Build a client context with an explicit set of headers to send.
     *
     * <p>Transport-controlled and hop-by-hop headers are rejected because the
     * generated client or HTTP stack owns them. Headers remain readable through
     * {@link #header(String)} and {@link #headers()}.
     */
    public static TwirpContext ofOutboundHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> snapshot = snapshotOutboundHeaders(headers);
        Map<String, List<String>> immutable = Collections.unmodifiableMap(snapshot);
        return new TwirpContext(immutable, immutable, null);
    }

    /** An empty context — no headers, no security. */
    public static TwirpContext empty() {
        return new TwirpContext(Collections.emptyMap(), Collections.emptyMap(), null);
    }

    /**
     * The first value of the named header, if present. Header lookup is
     * case-insensitive.
     */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.get(0));
    }

    /**
     * All values of the named header (case-insensitive), or an empty list if
     * the header is absent.
     */
    public List<String> headerValues(String name) {
        List<String> values = headers.get(name);
        return values == null ? List.of() : values;
    }

    /**
     * The full, unmodifiable, case-insensitive header snapshot. Keys iterate in
     * a case-insensitive order; values preserve wire order.
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * The explicit, unmodifiable headers eligible for client-side propagation.
     * Server-created contexts always return an empty map here.
     */
    public Map<String, List<String>> outboundHeaders() {
        return outboundHeaders;
    }

    /**
     * The authenticated principal, if an authentication layer set one on the
     * {@link SecurityContext}. Empty when unauthenticated or when no
     * {@code SecurityContext} is present.
     *
     * <p>The returned {@link Principal} is the base type; downcast to your
     * application principal if you registered one (for example via
     * dropwizard-auth).
     */
    public Optional<Principal> principal() {
        return security == null ? Optional.empty() : Optional.ofNullable(security.getUserPrincipal());
    }

    /**
     * Whether the caller is in the given role. Delegates to the wrapped
     * {@link SecurityContext#isUserInRole(String)} — which, under
     * dropwizard-auth, defers to your application's {@code Authorizer}. Always
     * {@code false} when no {@code SecurityContext} is present.
     */
    public boolean isUserInRole(String role) {
        return security != null && security.isUserInRole(role);
    }

    /**
     * The wrapped {@link SecurityContext}, if any — an escape hatch for the rare
     * caller that needs {@link SecurityContext#getAuthenticationScheme()} or
     * {@link SecurityContext#isSecure()}. Prefer {@link #principal()} and
     * {@link #isUserInRole(String)} for the common cases.
     */
    public Optional<SecurityContext> securityContext() {
        return Optional.ofNullable(security);
    }

    private static Map<String, List<String>> caseInsensitive() {
        return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    private static Map<String, List<String>> snapshotOutboundHeaders(
            Map<String, List<String>> headers) {
        Map<String, List<String>> snapshot = caseInsensitive();
        if (headers == null) {
            return snapshot;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("outbound header names must not be blank");
            }
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (TRANSPORT_CONTROLLED_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "outbound header is controlled by the HTTP transport: " + name);
            }
            List<String> values = entry.getValue();
            if (values == null || values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "outbound header values must not be null: " + name);
            }
            snapshot.put(name, List.copyOf(values));
        }
        return snapshot;
    }

    private static void copyRequestHeaders(Map<String, List<String>> target, HttpHeaders headers) {
        Map<String, List<String>> requestHeaders = headers.getRequestHeaders();
        if (requestHeaders == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                target.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
    }
}
