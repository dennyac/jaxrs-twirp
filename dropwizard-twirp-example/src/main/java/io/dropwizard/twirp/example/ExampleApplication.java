package io.dropwizard.twirp.example;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.twirp.TwirpBundle;
import io.dropwizard.twirp.example.haberdasher.HaberdasherResource;

/**
 * Dropwizard application exposing the Haberdasher Twirp service.
 *
 * <p>Two pieces of wiring are required:
 * <ol>
 *   <li>Add {@link TwirpBundle} during {@link #initialize(Bootstrap)} so the
 *       protobuf/JSON body providers and exception mappers are registered with
 *       Jersey.</li>
 *   <li>Register the generated {@code HaberdasherResource} JAX-RS resource,
 *       passing it an implementation of the generated {@code Haberdasher}
 *       interface.</li>
 * </ol>
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
