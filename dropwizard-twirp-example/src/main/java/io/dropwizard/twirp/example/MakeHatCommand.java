package io.dropwizard.twirp.example;

import io.dropwizard.core.cli.Command;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.twirp.TwirpException;
import io.dropwizard.twirp.TwirpMediaTypes;
import io.dropwizard.twirp.example.haberdasher.Haberdasher;
import io.dropwizard.twirp.example.haberdasher.HaberdasherClient;
import io.dropwizard.twirp.example.haberdasher.Hat;
import io.dropwizard.twirp.example.haberdasher.Size;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.PrintStream;

/**
 * Dropwizard {@link Command} that calls the running Haberdasher service from
 * the same packaged jar.
 *
 * <p>This is the client-side mirror of {@link ExampleApplication}: where the
 * default {@code server} command stands up the Twirp endpoint, {@code make-hat}
 * invokes the generated {@link HaberdasherClient} against it. It exists so
 * newcomers can see the full server/client loop end-to-end without writing any
 * code:
 *
 * <pre>{@code
 * # Terminal 1: start the server.
 * $ java -jar dropwizard-twirp-example.jar server example.yml
 *
 * # Terminal 2: ask it to make a hat.
 * $ java -jar dropwizard-twirp-example.jar make-hat --inches 12
 * 12" red fedora
 *
 * # Same call over the JSON wire format.
 * $ java -jar dropwizard-twirp-example.jar make-hat --inches 12 --json
 * 12" blue fedora
 *
 * # Server-side TwirpExceptions surface as a non-zero exit + readable line.
 * $ java -jar dropwizard-twirp-example.jar make-hat --inches 0
 * RPC failed: INVALID_ARGUMENT - inches must be > 0
 * }</pre>
 *
 * <p>The interesting bits, in order:
 * <ol>
 *   <li>{@link ClientBuilder#newClient()} produces a vanilla JAX-RS {@code Client}.
 *       If you're inside a Dropwizard app you'd normally use
 *       {@code new io.dropwizard.client.JerseyClientBuilder(env).build(name)} so
 *       the client gets metrics, healthchecks, lifecycle, and the shared
 *       Jersey config — but a one-shot CLI command doesn't have an
 *       {@code Environment}, so the plain builder is enough here.</li>
 *   <li>{@link HaberdasherClient} is the codegen output. Its constructor
 *       registers the protobuf + JSON {@code MessageBodyReader}/
 *       {@code MessageBodyWriter} on the supplied {@code WebTarget}, so no
 *       extra Jersey wiring is required.</li>
 *   <li>{@link TwirpException} is thrown for both wire-level errors (server
 *       returned a non-2xx Twirp error envelope) and transport failures
 *       (network unreachable, malformed body, …). The {@code ErrorCode} on
 *       the exception always reflects the spec, never a raw HTTP status.</li>
 * </ol>
 */
public class MakeHatCommand extends Command {

    public static final String DEFAULT_NAME = "make-hat";
    private static final String DEFAULT_DESCRIPTION =
            "Call the Haberdasher Twirp service using the generated HaberdasherClient.";

    private final PrintStream out;
    private final PrintStream err;

    public MakeHatCommand() {
        this(DEFAULT_NAME, DEFAULT_DESCRIPTION, System.out, System.err);
    }

    // Visible for testing — lets tests capture stdout/stderr without polluting the JVM streams.
    MakeHatCommand(String name, String description, PrintStream out, PrintStream err) {
        super(name, description);
        this.out = out;
        this.err = err;
    }

    @Override
    public void configure(Subparser subparser) {
        subparser.addArgument("--url")
                .dest("url")
                .setDefault("http://localhost:8080")
                .metavar("URL")
                .help("Base URL of the Haberdasher service (no trailing slash, no "
                        + "/twirp prefix). Default: http://localhost:8080.");

        subparser.addArgument("--inches")
                .dest("inches")
                .type(Integer.class)
                .setDefault(12)
                .metavar("N")
                .help("Hat size to request, in inches. Default: 12. Use 0 to "
                        + "trigger the server-side INVALID_ARGUMENT path.");

        subparser.addArgument("--json")
                .dest("json")
                .action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
                .help("Use application/json on the wire instead of the default "
                        + "application/protobuf. Useful for poking with curl-style tools.");
    }

    @Override
    public void run(Bootstrap<?> bootstrap, Namespace namespace) {
        String url = namespace.getString("url");
        int inches = namespace.getInt("inches");
        boolean json = Boolean.TRUE.equals(namespace.getBoolean("json"));

        // exitCode lets the JVM signal success/failure to shell scripts:
        //   0 = hat returned ok
        //   1 = remote returned a TwirpException (including transport failures
        //       which the client maps to ErrorCode.UNAVAILABLE)
        int exitCode = call(url, inches, json);
        if (exitCode != 0) {
            // Don't call System.exit during tests — they share the JVM and
            // killing it would tank the test runner. The production main()
            // wraps this with the exit call.
            throw new RpcFailedException(exitCode);
        }
    }

    int call(String url, int inches, boolean json) {
        // Use a try-with-resources so we don't leak the Jersey client / its
        // connection pool when the command finishes.
        try (Client client = ClientBuilder.newClient()) {
            Haberdasher remote = json
                    ? new HaberdasherClient(client.target(url), TwirpMediaTypes.APPLICATION_JSON)
                    : new HaberdasherClient(client.target(url));

            try {
                Hat hat = remote.makeHat(Size.newBuilder().setInches(inches).build());
                out.println(hat.getInches() + "\" " + hat.getColor() + " " + hat.getStyleName());
                return 0;
            } catch (TwirpException ex) {
                err.println("RPC failed: " + ex.getErrorCode() + " - " + ex.getMessage());
                if (!ex.getMeta().isEmpty()) {
                    err.println("  meta: " + ex.getMeta());
                }
                return 1;
            }
        }
    }

    /**
     * Marker exception used to signal a non-zero exit out of {@link #run} without
     * tearing down the JVM (which would break tests). The example's
     * {@link ExampleApplication#main(String[])} is unaffected because Dropwizard's
     * default {@code Cli} entry point reports the exception and returns a
     * non-zero exit code.
     */
    static final class RpcFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int exitCode;

        RpcFailedException(int exitCode) {
            super("RPC failed (exit code " + exitCode + ")");
            this.exitCode = exitCode;
        }
    }
}
