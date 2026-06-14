package io.dropwizard.twirp.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwirpGenerateCommandTest {

    @Test
    void buildRequestPopulatesParameterAndFileToGenerate() {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile())
                .build();

        CodeGeneratorRequest req = TwirpGenerateCommand.buildRequest(
                set, List.of("haberdasher.proto"), "/api", /* noClient= */ false);

        assertThat(req.getParameter()).isEqualTo("prefix=/api");
        assertThat(req.getProtoFileList()).hasSize(1);
        assertThat(req.getFileToGenerateList()).containsExactly("haberdasher.proto");
    }

    @Test
    void buildRequestPropagatesNoClientFlag() {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile()).build();

        CodeGeneratorRequest req = TwirpGenerateCommand.buildRequest(
                set, List.of("haberdasher.proto"), "/twirp", /* noClient= */ true);

        assertThat(req.getParameter()).isEqualTo("prefix=/twirp,client=false");
    }

    @Test
    void buildRequestStripsLeadingDotSlash() {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile())
                .build();

        // ./haberdasher.proto should still be recognized as the descriptor named
        // haberdasher.proto so the plugin emits sources for it.
        CodeGeneratorRequest req = TwirpGenerateCommand.buildRequest(
                set, List.of("./haberdasher.proto"), "/twirp", false);

        assertThat(req.getFileToGenerateList()).containsExactly("haberdasher.proto");
    }

    @Test
    void buildRequestExcludesImportedDescriptorsFromFileToGenerate() {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(importedFile())
                .addFile(haberdasherFile())
                .build();

        CodeGeneratorRequest req = TwirpGenerateCommand.buildRequest(
                set, List.of("haberdasher.proto"), "/twirp", false);

        // Imported proto descriptors must be in proto_file (so type resolution
        // works) but NOT in file_to_generate (so we don't emit code for them).
        assertThat(req.getProtoFileList()).hasSize(2);
        assertThat(req.getFileToGenerateList()).containsExactly("haberdasher.proto");
    }

    @Test
    void runWritesGeneratedFilesToOutputDir(@TempDir Path tempDir) throws Exception {
        // Use a forged "protoc" that writes a precomputed FileDescriptorSet to
        // the path requested via --descriptor_set_out, so we don't require an
        // actual protoc binary on the CI box.
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile())
                .build();
        Path fakeProtoc = writeFakeProtoc(tempDir, set);
        Path outputDir = tempDir.resolve("out");

        runCommand(new String[]{
                "--protoc", fakeProtoc.toString(),
                "--proto", "haberdasher.proto",
                "-I", ".",
                "-o", outputDir.toString()
        });

        try (Stream<Path> files = Files.walk(outputDir)) {
            List<String> names = files.filter(Files::isRegularFile)
                    .map(p -> outputDir.relativize(p).toString().replace('\\', '/'))
                    .toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                    "com/twitch/twirp/example/haberdasher/HaberdasherResource.java",
                    "com/twitch/twirp/example/haberdasher/HaberdasherClient.java");
        }

        String resource = Files.readString(outputDir.resolve(
                "com/twitch/twirp/example/haberdasher/HaberdasherResource.java"));
        assertThat(resource).contains("@Path(\"/twirp/twitch.twirp.example.Haberdasher\")");
    }

    @Test
    void noClientFlagSkipsClientCodegen(@TempDir Path tempDir) throws Exception {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile()).build();
        Path fakeProtoc = writeFakeProtoc(tempDir, set);
        Path outputDir = tempDir.resolve("out");

        runCommand(new String[]{
                "--protoc", fakeProtoc.toString(),
                "--proto", "haberdasher.proto",
                "-o", outputDir.toString(),
                "--no-client"
        });

        try (Stream<Path> files = Files.walk(outputDir)) {
            Set<String> names = files.filter(Files::isRegularFile)
                    .map(p -> outputDir.relativize(p).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrder(
                    "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                    "com/twitch/twirp/example/haberdasher/HaberdasherResource.java");
        }
    }

    @Test
    void customPrefixIsRespected(@TempDir Path tempDir) throws Exception {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(haberdasherFile()).build();
        Path fakeProtoc = writeFakeProtoc(tempDir, set);
        Path outputDir = tempDir.resolve("out");

        runCommand(new String[]{
                "--protoc", fakeProtoc.toString(),
                "--proto", "haberdasher.proto",
                "-o", outputDir.toString(),
                "--prefix", "/rpc"
        });

        String resource = Files.readString(outputDir.resolve(
                "com/twitch/twirp/example/haberdasher/HaberdasherResource.java"));
        assertThat(resource).contains("@Path(\"/rpc/twitch.twirp.example.Haberdasher\")");
    }

    @Test
    void protocFailureSurfacesAsRuntimeError(@TempDir Path tempDir) throws Exception {
        // A "protoc" that always exits non-zero with a clear message.
        Path fakeProtoc = tempDir.resolve("failing-protoc.sh");
        Files.writeString(fakeProtoc, "#!/bin/sh\necho 'fake protoc error message'\nexit 2\n");
        fakeProtoc.toFile().setExecutable(true);

        assertThatThrownBy(() -> runCommand(new String[]{
                "--protoc", fakeProtoc.toString(),
                "--proto", "haberdasher.proto",
                "-o", tempDir.resolve("out").toString()
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status 2")
                .hasMessageContaining("fake protoc error");
    }

    @Test
    void missingRequiredArgsFailsAtParseTime() throws Exception {
        // --proto and -o are required; argparse4j surfaces this via an
        // ArgumentParserException which Cli normally turns into stderr output,
        // but here we drive the parser directly to assert the constraint.
        TwirpGenerateCommand cmd = new TwirpGenerateCommand();
        ArgumentParser parser = net.sourceforge.argparse4j.ArgumentParsers.newFor("test").build();
        Subparsers subparsers = parser.addSubparsers();
        Subparser sub = subparsers.addParser(cmd.getName());
        cmd.configure(sub);

        assertThatThrownBy(() ->
                parser.parseArgs(new String[]{cmd.getName(), "--proto", "x.proto"}))
                .isInstanceOf(net.sourceforge.argparse4j.inf.ArgumentParserException.class);
    }

    // --- helpers --------------------------------------------------------

    /**
     * Drive the command through the same argparse4j parser Dropwizard would
     * wire up. We bypass the full Cli class so we don't have to build a
     * Configuration/Application pair for an operation that doesn't need either.
     */
    private static void runCommand(String[] args) throws Exception {
        TwirpGenerateCommand cmd = new TwirpGenerateCommand();
        ArgumentParser parser = net.sourceforge.argparse4j.ArgumentParsers.newFor("test").build();
        Subparsers subparsers = parser.addSubparsers();
        Subparser sub = subparsers.addParser(cmd.getName());
        cmd.configure(sub);

        List<String> all = new ArrayList<>();
        all.add(cmd.getName());
        for (String a : args) {
            all.add(a);
        }
        var ns = parser.parseArgs(all.toArray(new String[0]));
        cmd.run(null, ns);
    }

    /** A shell script that ignores everything except --descriptor_set_out=... and writes the supplied bytes there. */
    private static Path writeFakeProtoc(Path dir, FileDescriptorSet payload) throws Exception {
        Path desc = dir.resolve("payload.pb");
        Files.write(desc, payload.toByteArray());
        Path script = dir.resolve("fake-protoc.sh");
        // Find the --descriptor_set_out=PATH argument and `cp` our payload to it.
        // This matches the contract of TwirpGenerateCommand.runProtoc.
        Files.writeString(script,
                "#!/bin/sh\n"
                        + "for arg in \"$@\"; do\n"
                        + "  case \"$arg\" in\n"
                        + "    --descriptor_set_out=*)\n"
                        + "      out=\"${arg#--descriptor_set_out=}\"\n"
                        + "      cp \"" + desc.toAbsolutePath() + "\" \"$out\"\n"
                        + "      exit 0\n"
                        + "      ;;\n"
                        + "  esac\n"
                        + "done\n"
                        + "echo 'fake-protoc: no --descriptor_set_out flag' >&2\n"
                        + "exit 1\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private static FileDescriptorProto haberdasherFile() {
        DescriptorProto hat = DescriptorProto.newBuilder()
                .setName("Hat")
                .addField(FieldDescriptorProto.newBuilder().setName("color").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto size = DescriptorProto.newBuilder()
                .setName("Size")
                .addField(FieldDescriptorProto.newBuilder().setName("inches").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        ServiceDescriptorProto haberdasher = ServiceDescriptorProto.newBuilder()
                .setName("Haberdasher")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("MakeHat")
                        .setInputType(".twitch.twirp.example.Size")
                        .setOutputType(".twitch.twirp.example.Hat"))
                .build();
        return FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(hat)
                .addMessageType(size)
                .addService(haberdasher)
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();
    }

    private static FileDescriptorProto importedFile() {
        return FileDescriptorProto.newBuilder()
                .setName("imported.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Other"))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.imported")
                        .setJavaMultipleFiles(true))
                .build();
    }
}
