package io.dropwizard.twirp.protoc;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-functional core of the plugin: given a protoc {@link CodeGeneratorRequest},
 * produces a matching {@link CodeGeneratorResponse} with one Java source file
 * per Twirp service (one interface + one JAX-RS resource per service, joined
 * into the same file).
 */
public final class Plugin {

    /**
     * Generate Java sources for every {@code service} block in every file
     * listed in {@code request.getFileToGenerateList()}.
     */
    public CodeGeneratorResponse generate(CodeGeneratorRequest request) {
        Options options = Options.parse(request.getParameter());
        if (!options.generateServer() && !options.generateClient()) {
            // Reject the degenerate config uniformly across every invocation
            // path (Maven plugin, raw `protoc`, programmatic callers). Without
            // this guard you'd silently get only the service interface, which
            // is rarely useful and is better served by plain `protoc` with no
            // Twirp plugin at all.
            return CodeGeneratorResponse.newBuilder()
                    .setError("twirp-protoc: client=false and server=false "
                            + "cannot both be set; nothing useful would be generated.")
                    .build();
        }
        List<FileDescriptorProto> protos = request.getProtoFileList();
        TypeMapper types = TypeMapper.fromFiles(protos);

        ServiceGenerator serviceGen = new ServiceGenerator(types);
        ResourceGenerator resourceGen = new ResourceGenerator(options, types);
        ClientGenerator clientGen = new ClientGenerator(options, types);

        Map<String, FileDescriptorProto> byName = new HashMap<>();
        for (FileDescriptorProto file : protos) {
            byName.put(file.getName(), file);
        }

        CodeGeneratorResponse.Builder response = CodeGeneratorResponse.newBuilder();
        // Advertise that we understand proto3 optional fields; without this
        // protoc 3.15+ refuses to invoke us on protos that use them.
        response.setSupportedFeatures(
                CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.getNumber());

        for (String fileName : request.getFileToGenerateList()) {
            FileDescriptorProto file = byName.get(fileName);
            if (file == null) {
                continue;
            }
            if (file.getServiceCount() == 0) {
                continue;
            }
            for (ServiceDescriptorProto service : file.getServiceList()) {
                String javaPackage = TypeMapper.javaPackageOf(file);
                response.addFile(buildFile(serviceGen.generateInterface(service), javaPackage));
                ClassName serviceInterface = ClassName.get(javaPackage, service.getName());
                if (options.generateServer()) {
                    response.addFile(buildFile(
                            resourceGen.generateResource(file, service, serviceInterface),
                            javaPackage));
                }
                if (options.generateClient()) {
                    response.addFile(buildFile(
                            clientGen.generateClient(file, service, serviceInterface),
                            javaPackage));
                }
            }
        }

        return response.build();
    }

    private CodeGeneratorResponse.File buildFile(TypeSpec type, String javaPackage) {
        JavaFile javaFile = JavaFile.builder(javaPackage, type)
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
        String path = javaPackage.replace('.', '/') + "/" + type.name + ".java";
        return CodeGeneratorResponse.File.newBuilder()
                .setName(path)
                .setContent(javaFile.toString())
                .build();
    }
}
