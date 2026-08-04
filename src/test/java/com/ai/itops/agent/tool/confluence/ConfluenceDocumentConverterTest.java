package com.ai.itops.agent.tool.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceDocumentConverterTest {

    private final ConfluenceDocumentConverter converter = new ConfluenceDocumentConverter();

    @Test
    void shouldRemoveScriptsAndKeepUsefulDocumentStructure() {
        String markdown = converter.convert("""
                <h1>Redis 排障</h1><p>先检查连接池。</p>
                <ul><li>确认超时配置</li><li>查看 ERROR 日志</li></ul>
                <script>alert('ignore')</script>
                """, 1000);

        assertThat(markdown).contains("# Redis 排障")
                .contains("先检查连接池。")
                .contains("- 确认超时配置")
                .doesNotContain("alert")
                .doesNotContain("ignore");
    }

    @Test
    void shouldTruncateLongContentWithExplicitMarker() {
        String markdown = converter.convert("<p>abcdefghijklmnopqrstuvwxyz</p>", 8);

        assertThat(markdown).startsWith("abcdefgh")
                .contains("正文已按长度上限截断");
    }
}
