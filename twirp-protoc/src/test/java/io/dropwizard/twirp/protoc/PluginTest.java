package io.dropwizard.twirp.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.File;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginTest {

    @Test
    void generatesInterfaceAndResourceForServiceWithJavaPackage() {
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(/* multipleFiles= */ true))
                .build();

        CodeGeneratorResponse response = new Plugin().generate(req);

        // Plugin must advertise proto3 optional support so protoc 3.15+ allows
        // generating against protos that use optional scalar fields.
        assertThat(response.getSupportedFeatures())
                .isEqualTo((long) CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL.getNumber());

        Map<String, String> files = response.getFileList().stream()
                .collect(Collectors.toMap(File::getName, File::getContent));
        assertThat(files).containsOnlyKeys(
                "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherResource.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherClient.java");

        String iface = files.get("com/twitch/twirp/example/haberdasher/Haberdasher.java");
        assertThat(iface)
                .contains("package com.twitch.twirp.example.haberdasher;")
                .contains("public interface Haberdasher {")
                .contains("Hat makeHat(Size request) throws TwirpException;")
                .contains("import io.dropwizard.twirp.TwirpException;");

        String resource = files.get("com/twitch/twirp/example/haberdasher/HaberdasherResource.java");
        assertThat(resource)
                .contains("package com.twitch.twirp.example.haberdasher;")
                .contains("@Path(\"/twirp/twitch.twirp.example.Haberdasher\")")
                .contains("public final class HaberdasherResource {")
                .contains("private final Haberdasher service;")
                .contains("public HaberdasherResource(Haberdasher service) {")
                .contains("this.service = Objects.requireNonNull(service, \"service\");")
                .contains("@POST")
                .contains("@Path(\"/MakeHat\")")
                .contains("@Consumes({TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})")
                .contains("@Produces({TwirpMediaTypes.APPLICATION_PROTOBUF, TwirpMediaTypes.APPLICATION_JSON})")
                // Returns Response (not Hat directly) so we can set Content-Type
                // dynamically per request, mirroring the Twirp v7 spec.
                .contains("public Response makeHat(Size request, @Context HttpHeaders headers) {")
                .contains("Hat result = TwirpInvocations.invoke(\"MakeHat\", () -> service.makeHat(request));")
                .contains("return Response.ok(result).type(TwirpMediaTypes.responseType(headers)).build();");

        String client = files.get("com/twitch/twirp/example/haberdasher/HaberdasherClient.java");
        assertThat(client)
                .contains("package com.twitch.twirp.example.haberdasher;")
                .contains("public final class HaberdasherClient implements Haberdasher {")
                .contains("private final WebTarget target;")
                .contains("private final String contentType;")
                .contains("public HaberdasherClient(WebTarget baseTarget) {")
                .contains("this(baseTarget, TwirpMediaTypes.APPLICATION_PROTOBUF);")
                .contains("public HaberdasherClient(WebTarget baseTarget, String contentType) {")
                .contains("TwirpClients.registerProviders(baseTarget);")
                .contains("this.target = baseTarget.path(\"/twirp/twitch.twirp.example.Haberdasher\");")
                .contains("@Override")
                .contains("public Hat makeHat(Size request) throws TwirpException {")
                .contains("return TwirpClients.invoke(")
                .contains("target.path(\"/MakeHat\").request(contentType).accept(contentType),")
                .contains("Entity.entity(request, contentType),")
                .contains("Hat.class);");

        // The managed-client builder is opt-in (clientBuilder option); it must
        // NOT appear by default so the generated client stays dependency-light.
        assertThat(client)
                .doesNotContain("TwirpClientBuilder")
                .doesNotContain("builder(Environment");
    }

    @Test
    void clientBuilderFactoryEmittedWhenEnabled() {
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("clientBuilder=true")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        String client = new Plugin().generate(req).getFileList().stream()
                .filter(f -> f.getName().endsWith("HaberdasherClient.java"))
                .findFirst().orElseThrow().getContent();

        assertThat(client)
                .contains("import io.dropwizard.twirp.TwirpClientBuilder;")
                .contains("import io.dropwizard.core.setup.Environment;")
                .contains("import io.dropwizard.client.JerseyClientConfiguration;")
                .contains("public static TwirpClientBuilder<HaberdasherClient> builder(")
                .contains("Environment environment")
                .contains("JerseyClientConfiguration configuration")
                .contains("return TwirpClientBuilder.forService(HaberdasherClient::new)")
                .contains(".using(environment, configuration)")
                .contains(".clientName(\"HaberdasherClient\");");
    }

    @Test
    void clientGenerationCanBeDisabledViaOption() {
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("client=false")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        List<String> names = new Plugin().generate(req).getFileList().stream()
                .map(File::getName).toList();

        assertThat(names).containsExactlyInAnyOrder(
                "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherResource.java");
    }

    @Test
    void serverGenerationCanBeDisabledViaOption() {
        // Client-only module: emit the interface + client stub, no JAX-RS resource.
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("server=false")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        List<String> names = new Plugin().generate(req).getFileList().stream()
                .map(File::getName).toList();

        assertThat(names).containsExactlyInAnyOrder(
                "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherClient.java");
    }

    @Test
    void bothTogglesOffIsRejectedAsADegenerateConfig() {
        // The interface alone is rarely useful — if you wanted just protoc
        // message classes you'd skip the Twirp plugin entirely. Reject the
        // config uniformly so every invocation path (Maven, raw protoc,
        // programmatic) gets the same error instead of silently emitting an
        // interface no one will use.
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("client=false,server=false")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        CodeGeneratorResponse response = new Plugin().generate(req);

        assertThat(response.getFileList()).isEmpty();
        assertThat(response.getError())
                .contains("client=false and server=false")
                .contains("nothing useful would be generated");
    }

    @Test
    void clientHonorsCustomPathPrefix() {
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("prefix=/rpc")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        String client = new Plugin().generate(req).getFileList().stream()
                .filter(f -> f.getName().endsWith("HaberdasherClient.java"))
                .findFirst().orElseThrow().getContent();

        assertThat(client)
                .contains("this.target = baseTarget.path(\"/rpc/twitch.twirp.example.Haberdasher\");");
    }

    @Test
    void honorsCustomPathPrefix() {
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .setParameter("prefix=/rpc")
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(haberdasherFile(true))
                .build();

        Map<String, String> files = new Plugin().generate(req).getFileList().stream()
                .collect(Collectors.toMap(File::getName, File::getContent));
        assertThat(files.get("com/twitch/twirp/example/haberdasher/HaberdasherResource.java"))
                .contains("@Path(\"/rpc/twitch.twirp.example.Haberdasher\")");
    }

    @Test
    void nestedMessageTypesUseOuterClassWhenJavaMultipleFilesFalse() {
        // With java_multiple_files=false, the generated Hat/Size types live as
        // nested classes inside an outer class. Verify our resolver names them
        // correctly in the generated method signatures.
        FileDescriptorProto file = haberdasherFile(false);
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(file)
                .build();

        String resource = new Plugin().generate(req).getFileList().stream()
                .filter(f -> f.getName().endsWith("HaberdasherResource.java"))
                .findFirst().orElseThrow().getContent();

        // The outer class lives in the same package as the resource, so JavaPoet
        // qualifies the nested type rather than emitting a static import.
        assertThat(resource)
                .contains("public Response makeHat(HaberdasherProto.Size request, @Context HttpHeaders headers) {")
                .contains("private final Haberdasher service;");
    }

    @Test
    void servicesInImportedProtosAreNotGenerated() {
        // The "imported" file has a service but isn't listed in
        // file_to_generate — we should only generate code for files the user
        // explicitly asked for.
        FileDescriptorProto imported = FileDescriptorProto.newBuilder()
                .setName("imported.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .addMessageType(DescriptorProto.newBuilder().setName("Size"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Imported")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("DoIt")
                                .setInputType(".twitch.twirp.example.Size")
                                .setOutputType(".twitch.twirp.example.Hat")))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();
        FileDescriptorProto top = haberdasherFile(true);
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("haberdasher.proto")
                .addProtoFile(imported)
                .addProtoFile(top)
                .build();

        List<String> names = new Plugin().generate(req).getFileList().stream()
                .map(File::getName)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "com/twitch/twirp/example/haberdasher/Haberdasher.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherResource.java",
                "com/twitch/twirp/example/haberdasher/HaberdasherClient.java");
    }

    @Test
    void streamingRpcsAreRejected() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("stream.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .addMessageType(DescriptorProto.newBuilder().setName("Size"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Streamer")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Stream")
                                .setInputType(".twitch.twirp.example.Size")
                                .setOutputType(".twitch.twirp.example.Hat")
                                .setServerStreaming(true)))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();

        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("stream.proto")
                .addProtoFile(file)
                .build();

        assertThatThrownBy(() -> new Plugin().generate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streaming");
    }

    @Test
    void filesWithoutServicesProduceNoOutput() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("messages_only.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();
        CodeGeneratorRequest req = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("messages_only.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = new Plugin().generate(req);
        assertThat(response.getFileList()).isEmpty();
        assertThat(response.getError()).isEmpty();
    }

    private static FileDescriptorProto haberdasherFile(boolean multipleFiles) {
        DescriptorProto hat = DescriptorProto.newBuilder()
                .setName("Hat")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("color").setNumber(1).setType(Type.TYPE_STRING).setLabel(Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto size = DescriptorProto.newBuilder()
                .setName("Size")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inches").setNumber(1).setType(Type.TYPE_INT32).setLabel(Label.LABEL_OPTIONAL))
                .build();
        ServiceDescriptorProto haberdasher = ServiceDescriptorProto.newBuilder()
                .setName("Haberdasher")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("MakeHat")
                        .setInputType(".twitch.twirp.example.Size")
                        .setOutputType(".twitch.twirp.example.Hat"))
                .build();
        FileOptions.Builder options = FileOptions.newBuilder()
                .setJavaPackage("com.twitch.twirp.example.haberdasher")
                .setJavaMultipleFiles(multipleFiles);
        if (!multipleFiles) {
            options.setJavaOuterClassname("HaberdasherProto");
        }
        return FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(hat)
                .addMessageType(size)
                .addService(haberdasher)
                .setOptions(options)
                .build();
    }
}
