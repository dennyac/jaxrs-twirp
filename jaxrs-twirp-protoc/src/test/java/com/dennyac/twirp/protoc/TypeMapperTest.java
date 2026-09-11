package com.dennyac.twirp.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeMapperTest {

    @Test
    void mapsTopLevelMessageInOuterClassByDefault() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher"))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        ClassName cn = mapper.resolve(".twitch.twirp.example.Hat");
        assertThat(cn.canonicalName())
                .isEqualTo("com.twitch.twirp.example.haberdasher.Haberdasher.Hat");
    }

    @Test
    void honorsJavaMultipleFiles() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        assertThat(mapper.resolve(".twitch.twirp.example.Hat").canonicalName())
                .isEqualTo("com.twitch.twirp.example.haberdasher.Hat");
    }

    @Test
    void honorsExplicitOuterClassName() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaOuterClassname("HaberdasherProto"))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        assertThat(mapper.resolve(".twitch.twirp.example.Hat").canonicalName())
                .isEqualTo("com.twitch.twirp.example.haberdasher.HaberdasherProto.Hat");
    }

    @ParameterizedTest
    @MethodSource("filesWithNameCollisions")
    void inferredWrapperAvoidsDeclaredTypeAndServiceNames(FileDescriptorProto file) {
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        assertThat(mapper.resolve(".example.Hat").canonicalName())
                .isEqualTo("com.example.HaberdasherOuterClass.Hat");
    }

    @ParameterizedTest
    @MethodSource("filesWithNameCollisions")
    void explicitWrapperTakesPrecedenceOverInferredCollisions(FileDescriptorProto file) {
        FileDescriptorProto overridden = file.toBuilder()
                .setOptions(file.getOptions().toBuilder().setJavaOuterClassname("CustomMessages"))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(overridden));

        assertThat(mapper.resolve(".example.Hat").canonicalName())
                .isEqualTo("com.example.CustomMessages.Hat");
    }

    @ParameterizedTest
    @MethodSource("filesWithNameCollisions")
    void multipleFilesDoesNotNestMessagesInsideRenamedWrapper(FileDescriptorProto file) {
        FileDescriptorProto multipleFiles = file.toBuilder()
                .setOptions(file.getOptions().toBuilder().setJavaMultipleFiles(true))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(multipleFiles));

        assertThat(mapper.resolve(".example.Hat").canonicalName())
                .isEqualTo("com.example.Hat");
    }

    @Test
    void inferredWrapperCollisionIsCaseSensitive() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .addService(ServiceDescriptorProto.newBuilder().setName("haberdasher"))
                .build();

        assertThat(TypeMapper.outerClassNameOf(file)).isEqualTo("Haberdasher");
    }

    @Test
    void declarationsInOtherFilesDoNotRenameTheWrapper() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .build();
        FileDescriptorProto other = FileDescriptorProto.newBuilder()
                .setName("other.proto")
                .setPackage("other")
                .addMessageType(DescriptorProto.newBuilder().setName("Haberdasher"))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file, other));

        assertThat(mapper.resolve(".example.Hat").canonicalName())
                .isEqualTo("example.Haberdasher.Hat");
    }

    private static Stream<FileDescriptorProto> filesWithNameCollisions() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("example")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.example"))
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .build();
        DescriptorProto message = DescriptorProto.newBuilder().setName("Haberdasher").build();
        EnumDescriptorProto enumeration = EnumDescriptorProto.newBuilder().setName("Haberdasher").build();
        return Stream.of(
                file.toBuilder().addMessageType(message).build(),
                file.toBuilder().addEnumType(enumeration).build(),
                file.toBuilder().addService(ServiceDescriptorProto.newBuilder().setName("Haberdasher")).build(),
                file.toBuilder().addMessageType(DescriptorProto.newBuilder()
                        .setName("Parent")
                        .addNestedType(DescriptorProto.newBuilder()
                                .setName("Child")
                                .addNestedType(message))).build(),
                file.toBuilder().addMessageType(DescriptorProto.newBuilder()
                        .setName("Parent")
                        .addNestedType(DescriptorProto.newBuilder()
                                .setName("Child")
                                .addEnumType(enumeration))).build());
    }

    @Test
    void fallsBackToProtoPackageWhenJavaPackageMissing() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder().setName("Hat"))
                .setOptions(FileOptions.newBuilder().setJavaMultipleFiles(true))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        assertThat(mapper.resolve(".twitch.twirp.example.Hat").canonicalName())
                .isEqualTo("twitch.twirp.example.Hat");
    }

    @Test
    void supportsNestedMessages() {
        DescriptorProto inner = DescriptorProto.newBuilder().setName("Inner").build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("haberdasher.proto")
                .setPackage("twitch.twirp.example")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Outer")
                        .addNestedType(inner))
                .setOptions(FileOptions.newBuilder()
                        .setJavaPackage("com.twitch.twirp.example.haberdasher")
                        .setJavaMultipleFiles(true))
                .build();
        TypeMapper mapper = TypeMapper.fromFiles(List.of(file));

        assertThat(mapper.resolve(".twitch.twirp.example.Outer.Inner").canonicalName())
                .isEqualTo("com.twitch.twirp.example.haberdasher.Outer.Inner");
    }

    @Test
    void unknownTypeThrowsHelpfulMessage() {
        TypeMapper mapper = TypeMapper.fromFiles(Collections.emptyList());
        assertThatThrownBy(() -> mapper.resolve(".does.not.Exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".does.not.Exist");
    }
}
