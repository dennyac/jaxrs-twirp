package io.dropwizard.twirp.example;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.twirp.TwirpBundle;
import io.dropwizard.twirp.example.haberdasher.HaberdasherResource;

/**
 * Dropwizard application exposing the Haberdasher Twirp service.
 *
 * <p>The interesting bits are <b>elsewhere</b>:
 *
 * <ul>
 *   <li><b>Code generation</b> happens at build time via
 *       {@code protobuf-maven-plugin} wired in {@code pom.xml}. Every
 *       {@code mvn compile} regenerates protobuf message classes plus the
 *       Twirp service interface, JAX-RS resource, and JAX-RS client into
 *       {@code target/generated-sources/protobuf/java/}. See
 *       {@code README.md} for the configuration and for how non-Maven
 *       users can drive {@code dropwizard-twirp-protoc} as a raw protoc
 *       plugin instead.</li>
 *
 *   <li>The <b>runtime wiring</b> for a Twirp service in a Dropwizard app
 *       is exactly what you see in this class:
 *       <ol>
 *         <li>Add {@link TwirpBundle} during {@link #initialize(Bootstrap)}
 *             so the protobuf/JSON body readers/writers and the Twirp error
 *             mapper are registered with Jersey.</li>
 *         <li>Register your generated {@code <Service>Resource} in
 *             {@link #run}, passing it an implementation of the generated
 *             service interface.</li>
 *       </ol>
 *       That's the whole story — no separate server.</li>
 * </ul>
 */
public class ExampleApplication extends Application<ExampleConfiguration> {

    public static void main(String[] args) throws Exception {
        new ExampleApplication().run(args);
    }

    @Override
    public String getName() {
        return "dropwizard-twirp-example";
    }

    @Override
    public void initialize(Bootstrap<ExampleConfiguration> bootstrap) {
        bootstrap.addBundle(new TwirpBundle<>());
    }

    @Override
    public void run(ExampleConfiguration configuration, Environment environment) {
        environment.jersey().register(new HaberdasherResource(new HaberdasherImpl()));
    }
}
