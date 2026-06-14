package io.dropwizard.twirp.protoc;

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
    void defaultOuterClassNameStripsExtensionAndCapitalizes() {
        assertThat(JavaNaming.defaultOuterClassName("haberdasher.proto"))
                .isEqualTo("Haberdasher");
        assertThat(JavaNaming.defaultOuterClassName("path/to/my_service.proto"))
                .isEqualTo("MyService");
        assertThat(JavaNaming.defaultOuterClassName("foo_bar_baz.proto"))
                .isEqualTo("FooBarBaz");
    }
}
