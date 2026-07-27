/**
 * Framework-agnostic JAX-RS runtime for the
 * <a href="https://twitchtv.github.io/twirp/docs/spec_v7.html">Twirp RPC protocol (v7)</a>.
 *
 * <p>This package provides the Jersey/JAX-RS body providers ({@code codec}),
 * error model and exception mappers ({@code errors}), route-scoped error
 * handling, media types, auth-filter binding, and the client/server helpers
 * that generated Twirp code relies on. It depends only on the JAX-RS API,
 * protobuf, Jackson, and SLF4J — not on Dropwizard.
 *
 * <p>Dropwizard applications add the {@code dropwizard-twirp} module, whose
 * {@code TwirpBundle} registers these providers on the Jersey environment.
 */
package com.dennyac.twirp;
