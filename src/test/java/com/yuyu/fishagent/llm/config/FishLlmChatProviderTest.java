package com.yuyu.fishagent.llm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FishLlmChatProviderTest {

    @Test
    void parse_blank_defaultsToOllama() {
        assertThat(FishLlmChatProvider.parse(null)).isEqualTo(FishLlmChatProvider.OLLAMA);
        assertThat(FishLlmChatProvider.parse("  ")).isEqualTo(FishLlmChatProvider.OLLAMA);
    }

    @Test
    void parse_caseInsensitive() {
        assertThat(FishLlmChatProvider.parse("ollama")).isEqualTo(FishLlmChatProvider.OLLAMA);
        assertThat(FishLlmChatProvider.parse("OLLAMA")).isEqualTo(FishLlmChatProvider.OLLAMA);
    }

    @Test
    void toSpringAiModelChatValue_matchesAutoconfigure() {
        assertThat(FishLlmChatProvider.OLLAMA.toSpringAiModelChatValue()).isEqualTo("ollama");
        assertThat(FishLlmChatProvider.DEEPSEEK.toSpringAiModelChatValue()).isEqualTo("openai");
    }

    @Test
    void parse_deepseek_caseInsensitive() {
        assertThat(FishLlmChatProvider.parse("deepseek")).isEqualTo(FishLlmChatProvider.DEEPSEEK);
        assertThat(FishLlmChatProvider.parse("DEEPSEEK")).isEqualTo(FishLlmChatProvider.DEEPSEEK);
    }

    @Test
    void parse_unknown_throws() {
        assertThatThrownBy(() -> FishLlmChatProvider.parse("openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");
    }
}
