package io.dropwizard.twirp.protoc;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import io.dropwizard.core.cli.Command;
import io.dropwizard.core.setup.Bootstrap;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dropwizard {@link Command} that regenerates Twirp Java sources from a packaged
 * application jar without requiring a Maven build.
 *
 * <p>Wire this into your app's {@code initialize()}:
 *
 * <pre>{@code
 * public void initialize(Bootstrap<MyConfig> bootstrap) {
 *     bootstrap.addCommand(new TwirpGenerateCommand());
 * }
 * }</pre>
 *
 * <p>Then invoke from a built fat jar:
 *
 * <pre>{@code
 * java -jar my-app.jar twirp-generate \
 *     -I src/main/proto \
 *     --proto haberdasher.proto \
 *     --output-dir target/generated-sources/twirp
 * }</pre>
 *
 * <p>The command shells out to {@code protoc} once to produce a
 * {@link FileDescriptorSet} on disk, then runs the in-process
 * {@link Plugin#generate(CodeGeneratorRequest) plugin} against that descriptor
 * set and writes the resulting Java files to {@code --output-dir}.
 *
 * <p>The protoc binary is invoked with {@code --include_imports} and
 * {@code --include_source_info} so the plugin sees everything it needs even
 * when protos import each other across {@code -I} roots.
 */
public class TwirpGenerateCommand extends Command {

    /** Default name. Override by subclassing and passing a different name to {@link #TwirpGenerateCommand(String, String)}. */
    public static final String DEFAULT_NAME = "twirp-generate";
    private static final String DEFAULT_DESCRIPTION =
            "Regenerate Twirp Java sources (service interface, JAX-RS resource, Jersey client) from .proto files.";

    public TwirpGenerateCommand() {
        this(DEFAULT_NAME, DEFAULT_DESCRIPTION);
    }

    public TwirpGenerateCommand(String name, String description) {
        super(name, description);
    }

    @Override
    public void configure(Subparser subparser) {
        subparser.addArgument("--proto-path", "-I")
                .dest("protoPath")
                .action(Arguments.append())
                .metavar("DIR")
                .help("Directory to search for .proto files. May be repeated. Defaults to "
                        + "the current working directory if none are supplied.");

        subparser.addArgument("--proto")
                .dest("proto")
                .required(true)
                .action(Arguments.append())
                .metavar("FILE")
                .help("Path to a .proto file to generate from, relative to one of the "
                        + "--proto-path roots. May be repeated.");

        subparser.addArgument("--output-dir", "-o")
                .dest("outputDir")
                .required(true)
                .metavar("DIR")
                .help("Directory to write generated Java sources into. Created if missing.");

        subparser.addArgument("--prefix")
                .dest("prefix")
                .setDefault(Options.DEFAULT_PREFIX)
                .metavar("PATH")
                .help("URL prefix prepended to every generated @Path. Default: /twirp.");

        subparser.addArgument("--no-client")
                .dest("noClient")
                .action(Arguments.storeTrue())
                .help("Skip generating the Jersey client stub. Useful on server-only "
                        + "deployments that don't want a WebTarget dependency.");

        subparser.addArgument("--protoc")
                .dest("protoc")
                .setDefault("protoc")
                .metavar("PATH")
                .help("Path to the protoc binary. Default: 'protoc' on PATH.");
    }

    @Override
    public void run(Bootstrap<?> bootstrap, Namespace namespace) throws Exception {
        List<String> protoPaths = listOrDefault(namespace.getList("protoPath"), List.of("."));
        List<String> protos = namespace.getList("proto");
        Path outputDir = Paths.get(namespace.getString("outputDir"));
        String prefix = namespace.getString("prefix");
        boolean noClient = Boolean.TRUE.equals(namespace.getBoolean("noClient"));
        String protocBinary = namespace.getString("protoc");

        Files.createDirectories(outputDir);

        Path descriptorSet = Files.createTempFile("twirp-descriptor-", ".pb");
        try {
            runProtoc(protocBinary, protoPaths, protos, descriptorSet);
            FileDescriptorSet set;
            try (var in = Files.newInputStream(descriptorSet)) {
                set = FileDescriptorSet.parseFrom(in);
            }
            CodeGeneratorRequest request = buildRequest(set, protos, prefix, noClient);
            CodeGeneratorResponse response = new Plugin().generate(request);
            if (!response.getError().isEmpty()) {
                throw new IllegalStateException("twirp-generate failed: " + response.getError());
            }
            int written = writeResponse(response, outputDir);
            System.out.println("Wrote " + written + " file(s) to " + outputDir.toAbsolutePath());
        } finally {
            Files.deleteIfExists(descriptorSet);
        }
    }

    /**
     * Invoke {@code protoc --descriptor_set_out=...} so we get a binary
     * {@link FileDescriptorSet} we can feed straight into {@link Plugin}.
     */
    void runProtoc(String protocBinary, List<String> protoPaths, List<String> protos,
                          Path descriptorSet) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(protocBinary);
        cmd.add("--include_imports");
        cmd.add("--include_source_info");
        cmd.add("--descriptor_set_out=" + descriptorSet.toAbsolutePath());
        for (String path : protoPaths) {
            cmd.add("-I");
            cmd.add(path);
        }
        cmd.addAll(protos);

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("protoc exited with status " + exit
                    + ": " + String.join(" ", cmd) + System.lineSeparator() + output);
        }
    }

    /**
     * Build the {@link CodeGeneratorRequest} the in-process plugin expects. We
     * have to populate both {@code file_to_generate} (the user-listed protos)
     * and {@code proto_file} (every transitively imported descriptor) per the
     * protoc plugin contract.
     */
    static CodeGeneratorRequest buildRequest(FileDescriptorSet set, List<String> protos,
                                             String prefix, boolean noClient) {
        StringBuilder parameter = new StringBuilder();
        parameter.append("prefix=").append(prefix);
        if (noClient) {
            parameter.append(",client=false");
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String proto : protos) {
            wanted.add(stripLeading(proto));
        }
        CodeGeneratorRequest.Builder request = CodeGeneratorRequest.newBuilder()
                .setParameter(parameter.toString())
                .addAllProtoFile(set.getFileList());
        // Only ask the plugin to generate files the user actually listed; the
        // remaining descriptors are present so cross-file type resolution works.
        for (var file : set.getFileList()) {
            if (wanted.contains(file.getName())) {
                request.addFileToGenerate(file.getName());
            }
        }
        // If a user supplies a path that doesn't match a descriptor file name
        // verbatim (e.g. absolute path vs relative-to-include), pass it through
        // so protoc-style "file not generated" diagnostics surface from the
        // plugin instead of being silently skipped.
        for (String proto : wanted) {
            if (request.getFileToGenerateList().stream().noneMatch(proto::equals)) {
                request.addFileToGenerate(proto);
            }
        }
        return request.build();
    }

    private static int writeResponse(CodeGeneratorResponse response, Path outputDir) throws IOException {
        int count = 0;
        for (CodeGeneratorResponse.File file : response.getFileList()) {
            Path target = outputDir.resolve(file.getName());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getContent(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            count++;
        }
        return count;
    }

    private static <T> List<T> listOrDefault(List<T> list, List<T> fallback) {
        if (list == null || list.isEmpty()) {
            return fallback;
        }
        return Collections.unmodifiableList(list);
    }

    private static String stripLeading(String s) {
        // Tolerate both "./foo.proto" and "foo.proto".
        if (s.startsWith("./")) {
            return s.substring(2);
        }
        return s;
    }
}
