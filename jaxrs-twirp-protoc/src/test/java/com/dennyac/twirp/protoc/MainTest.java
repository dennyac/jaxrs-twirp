package com.dennyac.twirp.protoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @Test
    void noArgsKeepsParameterUnchanged() {
        assertThat(Main.mergeOptions("prefix=/rpc", new String[] {})).isEqualTo("prefix=/rpc");
        assertThat(Main.mergeOptions("prefix=/rpc", null)).isEqualTo("prefix=/rpc");
    }

    @Test
    void nullParameterWithoutArgsBecomesEmpty() {
        assertThat(Main.mergeOptions(null, new String[] {})).isEmpty();
    }

    @Test
    void argsBecomeTheParameterWhenProtocParameterIsEmpty() {
        assertThat(Main.mergeOptions(null, new String[] {"context=true"})).isEqualTo("context=true");
        assertThat(Main.mergeOptions("", new String[] {"context=true"})).isEqualTo("context=true");
    }

    @Test
    void argsAreAppendedAfterAnExistingParameter() {
        assertThat(Main.mergeOptions("prefix=/rpc", new String[] {"context=true"}))
                .isEqualTo("prefix=/rpc,context=true");
    }

    @Test
    void multipleArgTokensAreCommaJoined() {
        assertThat(Main.mergeOptions("", new String[] {"context=true", "client=false"}))
                .isEqualTo("context=true,client=false");
    }

    @Test
    void blankArgTokensAreIgnored() {
        assertThat(Main.mergeOptions("prefix=/rpc", new String[] {"", "  ", "context=true"}))
                .isEqualTo("prefix=/rpc,context=true");
    }
}
