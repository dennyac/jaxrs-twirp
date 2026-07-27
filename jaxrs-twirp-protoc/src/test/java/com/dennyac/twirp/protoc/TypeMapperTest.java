package com.dennyac.twirp.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.squareup.javapoet.ClassName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

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
