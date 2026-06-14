package io.dropwizard.twirp.example;

import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link MakeHatCommand} end-to-end against the live {@link ExampleApplication}
 * so the documented client-side example never bit-rots: if the codegen ever stops
 * emitting a usable HaberdasherClient, or the wire format changes shape, this
 * test fails.
 *
 * <p>Each test builds a fresh {@link MakeHatCommand} with captured stdout/stderr,
 * exercises {@link MakeHatCommand#run(io.dropwizard.core.setup.Bootstrap, Namespace)}
 * via argparse4j, and asserts on what got printed.
 */
@ExtendWith(DropwizardExtensionsSupport.class)
class MakeHatCommandTest {

    private static final DropwizardAppExtension<ExampleConfiguration> APP =
            new DropwizardAppExtension<>(
                    ExampleApplication.class,
                    ResourceHelpers.resourceFilePath("example.yml"),
                    ConfigOverride.config("server.applicationConnectors[0].port", "0"),
                    ConfigOverride.config("server.adminConnectors[0].port", "0"));

    @Test
    void printsHatLineOnSuccess() throws Exception {
        Capture cap = new Capture();
        MakeHatCommand cmd = cap.command();

        cmd.run(null, parseArgs(cmd, "--url", baseUrl(), "--inches", "12"));

        // "12\" <color> fedora" — color is random but the size and style are
        // deterministic from HaberdasherImpl.
        String stdout = cap.stdout();
        assertThat(stdout.trim())
                .matches("12\" \\w+ fedora");
        assertThat(cap.stderr()).isEmpty();
    }

    @Test
    void usesJsonWireFormatWhenFlagSet() throws Exception {
        Capture cap = new Capture();
        MakeHatCommand cmd = cap.command();

        // We can't directly assert on the wire format from out here, but
        // exercising the --json path covers HaberdasherClient's JSON
        // constructor — if JSON encoding regressed this would surface as a
        // TwirpException printed to stderr.
        cmd.run(null, parseArgs(cmd, "--url", baseUrl(), "--inches", "7", "--json"));

        assertThat(cap.stdout().trim()).matches("7\" \\w+ bowler");
        assertThat(cap.stderr()).isEmpty();
    }

    @Test
    void reportsTwirpErrorsToStderrAndExitsNonZero() throws Exception {
        Capture cap = new Capture();
        MakeHatCommand cmd = cap.command();

        // inches=0 trips HaberdasherImpl's precondition -> INVALID_ARGUMENT.
        // We expect the command to throw RpcFailedException (exitCode=1) and
        // print a structured error line + the meta map.
        assertThatThrownBy(() -> cmd.run(null, parseArgs(cmd, "--url", baseUrl(), "--inches", "0")))
                .isInstanceOfSatisfying(MakeHatCommand.RpcFailedException.class,
                        ex -> assertThat(ex.exitCode).isEqualTo(1));

        assertThat(cap.stdout()).isEmpty();
        assertThat(cap.stderr())
                .contains("RPC failed: INVALID_ARGUMENT - inches must be > 0")
                .contains("argument=inches");
    }

    @Test
    void networkErrorsAreReportedAsUnavailable() throws Exception {
        Capture cap = new Capture();
        MakeHatCommand cmd = cap.command();

        // Port 1 has no listener -> transport failure -> UNAVAILABLE.
        assertThatThrownBy(() -> cmd.run(null, parseArgs(cmd, "--url", "http://localhost:1", "--inches", "7")))
                .isInstanceOf(MakeHatCommand.RpcFailedException.class);

        assertThat(cap.stderr()).contains("RPC failed: UNAVAILABLE");
    }

    private static String baseUrl() {
        return "http://localhost:" + APP.getLocalPort();
    }

    /**
     * Builds an argparse4j Namespace the way Dropwizard's Cli would, so we can
     * call {@link MakeHatCommand#run} without standing up a full Bootstrap.
     */
    private static Namespace parseArgs(MakeHatCommand cmd, String... args) throws Exception {
        ArgumentParser root = ArgumentParsers.newFor("test").build();
        Subparser sub = root.addSubparsers().dest("cmd").addParser(MakeHatCommand.DEFAULT_NAME);
        cmd.configure(sub);

        // Prepend the subcommand name so argparse4j routes the rest of the
        // args at our subparser.
        String[] all = new String[args.length + 1];
        all[0] = MakeHatCommand.DEFAULT_NAME;
        System.arraycopy(args, 0, all, 1, args.length);
        return root.parseArgs(all);
    }

    /** Tiny helper that snapshots stdout/stderr for one MakeHatCommand invocation. */
    private static final class Capture {
        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        MakeHatCommand command() {
            return new MakeHatCommand(
                    MakeHatCommand.DEFAULT_NAME,
                    "test",
                    new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        }

        String stdout() {
            return outBuf.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            return errBuf.toString(StandardCharsets.UTF_8);
        }
    }
}
