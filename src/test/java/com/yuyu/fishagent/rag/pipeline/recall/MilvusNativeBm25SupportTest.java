package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusNativeBm25SupportTest {

    @Test
    void onlyMilvus25AndAboveSupportsNativeBm25() {
        assertThat(MilvusNativeBm25Support.serverSupportsBm25("v2.4.15")).isFalse();
        assertThat(MilvusNativeBm25Support.serverSupportsBm25("v2.5.0")).isTrue();
        assertThat(MilvusNativeBm25Support.serverSupportsBm25("2.6.1")).isTrue();
        assertThat(MilvusNativeBm25Support.serverSupportsBm25("unknown")).isFalse();
    }
}
