package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusLexicalSearchSupportTest {

    @Test
    void lexicalScoreUsesTheProvidedQuery() {
        double matching = MilvusLexicalSearchSupport.score("连接池 timeout", "数据库连接池 timeout 配置");
        double unrelated = MilvusLexicalSearchSupport.score("连接池 timeout", "部署 Kubernetes 的副本数");

        assertThat(matching).isGreaterThan(unrelated).isPositive();
        assertThat(unrelated).isZero();
    }
}
