package io.dropwizard.twirp.example;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.twirp.TwirpBundle;
import io.dropwizard.twirp.example.haberdasher.HaberdasherResource;
import io.dropwizard.twirp.protoc.TwirpGenerateCommand;

/**
 * Dropwizard application exposing the Haberdasher Twirp service.
 *
 * <p>There are two things to wire up:
 *
 * <ol>
 *   <li><b>Runtime ({@link TwirpBundle}).</b> Add the bundle during
 *       {@link #initialize(Bootstrap)} so the protobuf/JSON body providers and
 *       Twirp error mapper are registered with Jersey, then register your
 *       generated resource (which delegates to your impl of the generated
 *       service interface) in {@link #run}.</li>
 *
 *   <li><b>Codegen ({@link TwirpGenerateCommand}).</b> Optional but encouraged
 *       to ship in your fat jar so operators / CI / other apps can regenerate
 *       Twirp stubs without keeping a separate protoc plugin install:
 *
 *       <pre>{@code
 *       # Default — emits interface + resource + client into target/generated-sources/twirp.
 *       java -jar dropwizard-twirp-example.jar twirp-generate \
 *           -I src/main/proto --proto haberdasher.proto \
 *           -o target/generated-sources/twirp
 *
 *       # Server-only (e.g. the deployable service module):
 *       java -jar ... twirp-generate ... --no-client
 *
 *       # Client-only (e.g. a shared client jar consumed by other apps):
 *       java -jar ... twirp-generate ... --no-server
 *       }</pre>
 *
 *       The other codegen path is the Maven build-time plugin wired in
 *       {@code pom.xml}; see {@code README.md} for both.</li>
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
        bootstrap.addCommand(new TwirpGenerateCommand());
    }

    @Override
    public void run(ExampleConfiguration configuration, Environment environment) {
        environment.jersey().register(new HaberdasherResource(new HaberdasherImpl()));
    }
}
