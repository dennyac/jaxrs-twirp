package io.dropwizard.twirp;

import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.core.setup.Environment;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;

import java.util.Objects;

/**
 * Fluent helper that turns a base URI plus an HTTP client into a ready-to-use
 * generated Twirp client, so callers don't have to hand-wire a {@code WebTarget}.
 *
 * <p>Every generated {@code <Service>Client} already accepts a
 * {@code jakarta.ws.rs.client.WebTarget} directly — that constructor is the
 * low-level escape hatch (bring your own JAX-RS client, point it at any root,
 * inject a test target, run outside Dropwizard). This builder is sugar on top:
 * it owns the two fiddly steps — picking the wire format and resolving the root
 * {@code WebTarget} from a managed client — and hands the result to the
 * generated constructor.
 *
 * <p>Pass the generated client's two-arg constructor as the {@link ClientFactory}
 * (a {@code WebTarget, String -> Client} reference):
 *
 * <pre>{@code
 * HaberdasherClient hats = TwirpClientBuilder.forService(HaberdasherClient::new)
 *         .using(environment, configuration)        // managed dropwizard-client
 *         .baseUri("https://hats.prod.internal")
 *         .json()                                   // or .protobuf() (default)
 *         .clientName("haberdasher")                // names the client for metrics
 *         .build();
 * }</pre>
 *
 * <p>If you already have a {@code jakarta.ws.rs.client.Client} (for example a
 * single {@code JerseyClient} shared across several service stubs) use the
 * {@link #using(Client)} overload instead — the builder will not create or own
 * a second client in that case:
 *
 * <pre>{@code
 * HaberdasherClient hats = TwirpClientBuilder.forService(HaberdasherClient::new)
 *         .using(sharedJerseyClient)
 *         .baseUri("https://hats.prod.internal")
 *         .build();
 * }</pre>
 *
 * <p><strong>Dependency note:</strong> the {@link #using(Environment,
 * JerseyClientConfiguration)} overload builds a managed client via
 * {@code dropwizard-client}, which the runtime declares as an <em>optional</em>
 * dependency. Add {@code io.dropwizard:dropwizard-client} to your module to use
 * it. The {@link #using(Client)} overload only needs the JAX-RS API and works
 * without {@code dropwizard-client} on the classpath.
 *
 * @param <T> the generated client type produced by {@link #build()}
 */
public final class TwirpClientBuilder<T> {

    /**
     * The {@code (WebTarget, String) -> T} seam used to hand the resolved root
     * target and wire content type to a generated client constructor. Every
     * generated {@code <Service>Client} has a matching two-arg constructor, so
     * {@code SomeClient::new} satisfies this interface directly.
     */
    @FunctionalInterface
    public interface ClientFactory<T> {
        T create(WebTarget baseTarget, String contentType);
    }

    private final ClientFactory<T> factory;

    private String contentType = TwirpMediaTypes.APPLICATION_PROTOBUF;
    private String baseUri;
    private String clientName;

    // Exactly one client source is used by build(): a caller-supplied client
    // wins; otherwise an environment + configuration pair builds a managed one.
    private Client client;
    private Environment environment;
    private JerseyClientConfiguration configuration;

    private TwirpClientBuilder(ClientFactory<T> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Start a builder for a generated client, supplied as a constructor
     * reference (for example {@code HaberdasherClient::new}).
     */
    public static <T> TwirpClientBuilder<T> forService(ClientFactory<T> factory) {
        return new TwirpClientBuilder<>(factory);
    }

    /**
     * Use an existing JAX-RS {@code Client}. The builder will derive the root
     * {@code WebTarget} from it but will not manage its lifecycle — closing it
     * remains the caller's (or Dropwizard's) responsibility. Prefer this when
     * one managed client backs several service stubs.
     */
    public TwirpClientBuilder<T> using(Client client) {
        this.client = Objects.requireNonNull(client, "client");
        return this;
    }

    /**
     * Build a managed JAX-RS client from the application {@code Environment} and
     * a {@code JerseyClientConfiguration}. The created client is registered with
     * Dropwizard for metrics and lifecycle management.
     *
     * <p>Requires {@code io.dropwizard:dropwizard-client} on the classpath.
     */
    public TwirpClientBuilder<T> using(Environment environment, JerseyClientConfiguration configuration) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        return this;
    }

    /** The root URI of the remote application (scheme + host + port, no Twirp path). */
    public TwirpClientBuilder<T> baseUri(String baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        return this;
    }

    /** Use {@code application/protobuf} as the wire format. This is the default. */
    public TwirpClientBuilder<T> protobuf() {
        this.contentType = TwirpMediaTypes.APPLICATION_PROTOBUF;
        return this;
    }

    /** Use {@code application/json} as the wire format. */
    public TwirpClientBuilder<T> json() {
        this.contentType = TwirpMediaTypes.APPLICATION_JSON;
        return this;
    }

    /**
     * Use an explicit wire content type — one of
     * {@link TwirpMediaTypes#APPLICATION_PROTOBUF} or
     * {@link TwirpMediaTypes#APPLICATION_JSON}.
     */
    public TwirpClientBuilder<T> contentType(String contentType) {
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        return this;
    }

    /**
     * Name used when this builder creates a managed client via
     * {@link #using(Environment, JerseyClientConfiguration)}; surfaces in
     * Dropwizard client metrics and thread names. Must be unique per managed
     * client — if you build several stubs from one {@code Environment}, give each
     * a distinct name. When unset, a name is derived from the {@code baseUri}.
     * Ignored when an existing client is supplied through {@link #using(Client)}.
     */
    public TwirpClientBuilder<T> clientName(String clientName) {
        this.clientName = Objects.requireNonNull(clientName, "clientName");
        return this;
    }

    /**
     * Resolve the root {@code WebTarget} and construct the generated client.
     *
     * @throws NullPointerException  if {@link #baseUri(String)} was never set
     * @throws IllegalStateException if no client source was configured via a
     *                               {@code using(...)} overload
     */
    public T build() {
        Objects.requireNonNull(baseUri, "baseUri must be set before build()");
        WebTarget root = resolveClient().target(baseUri);
        return factory.create(root, contentType);
    }

    private Client resolveClient() {
        if (client != null) {
            return client;
        }
        if (environment != null && configuration != null) {
            return new JerseyClientBuilder(environment).using(configuration).build(resolveClientName());
        }
        throw new IllegalStateException(
                "No HTTP client configured: call using(Client) or "
                        + "using(Environment, JerseyClientConfiguration) before build()");
    }

    /**
     * The Dropwizard client name must be unique per managed client. If the caller
     * didn't set one, derive it from the baseUri so two stubs pointed at different
     * hosts don't collide on metric/thread names; fall back to a constant only when
     * no baseUri is available (build() requires one, so that's effectively never).
     */
    private String resolveClientName() {
        if (clientName != null) {
            return clientName;
        }
        return baseUri == null ? "twirp-client" : "twirp-client-" + baseUri;
    }
}
