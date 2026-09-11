// Copyright 2026 the jaxrs-twirp authors
// SPDX-License-Identifier: Apache-2.0

package com.dennyac.twirp.protoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaNamingTest {

    @Test
    void simpleUpperCamelToLowerCamel() {
        assertThat(JavaNaming.lowerCamelMethodName("MakeHat")).isEqualTo("makeHat");
        assertThat(JavaNaming.lowerCamelMethodName("Ping")).isEqualTo("ping");
    }

    @Test
    void alreadyLowerCamelIsUntouched() {
        assertThat(JavaNaming.lowerCamelMethodName("makeHat")).isEqualTo("makeHat");
    }

    @Test
    void allCapsBecomesAllLower() {
        assertThat(JavaNaming.lowerCamelMethodName("PING")).isEqualTo("ping");
    }

    @Test
    void leadingAcronymIsLoweredExceptLastUpperCharacter() {
        assertThat(JavaNaming.lowerCamelMethodName("HTTPSendBytes")).isEqualTo("httpSendBytes");
        assertThat(JavaNaming.lowerCamelMethodName("URLDecode")).isEqualTo("urlDecode");
    }

    @Test
    void singleCharIsLowered() {
        assertThat(JavaNaming.lowerCamelMethodName("X")).isEqualTo("x");
    }

    @Test
    void emptyOrNullReturnedAsIs() {
        assertThat(JavaNaming.lowerCamelMethodName("")).isEqualTo("");
        assertThat(JavaNaming.lowerCamelMethodName(null)).isNull();
    }

    @Test
    void rpcNamesThatLowercaseToJavaKeywordsAreSuffixed() {
        // An RPC named e.g. `Return`/`Import`/`Class` lowercases to a Java
        // reserved word; the method name must be mangled so the generated
        // interface/resource/client compile. The URL path keeps the raw name.
        assertThat(JavaNaming.lowerCamelMethodName("Return")).isEqualTo("return_");
        assertThat(JavaNaming.lowerCamelMethodName("Import")).isEqualTo("import_");
        assertThat(JavaNaming.lowerCamelMethodName("Class")).isEqualTo("class_");
        assertThat(JavaNaming.lowerCamelMethodName("Switch")).isEqualTo("switch_");
    }

    @Test
    void reservedLiteralsAreAlsoSuffixed() {
        // true/false/null are reserved literals, not valid identifiers either.
        assertThat(JavaNaming.lowerCamelMethodName("True")).isEqualTo("true_");
        assertThat(JavaNaming.lowerCamelMethodName("Null")).isEqualTo("null_");
    }

    @Test
    void nonKeywordNamesAreNotSuffixed() {
        // `returns`/`classes` merely start with a keyword; they are valid names.
        assertThat(JavaNaming.lowerCamelMethodName("Returns")).isEqualTo("returns");
        assertThat(JavaNaming.lowerCamelMethodName("MakeHat")).isEqualTo("makeHat");
    }

    @Test
    void defaultOuterClassNameStripsExtensionAndCapitalizes() {
        assertThat(JavaNaming.defaultOuterClassName("haberdasher.proto"))
                .isEqualTo("Haberdasher");
        assertThat(JavaNaming.defaultOuterClassName("path/to/my_service.proto"))
                .isEqualTo("MyService");
        assertThat(JavaNaming.defaultOuterClassName("foo_bar_baz.proto"))
                .isEqualTo("FooBarBaz");
    }
}
