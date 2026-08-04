package com.ai.itops.agent.tool.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 将 Confluence storage HTML 转为适合模型阅读的 Markdown 风格文本。
 */
@Component
public class ConfluenceDocumentConverter {

    public String convert(String html, int maxChars) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(html);
        document.select("script,style,noscript").remove();
        StringBuilder result = new StringBuilder();
        for (Element element : document.body().children()) {
            appendBlock(result, element);
        }
        String normalized = result.toString()
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
        if (maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars)) + "\n\n[正文已按长度上限截断]";
    }

    private void appendBlock(StringBuilder result, Element element) {
        String tag = element.tagName().toLowerCase();
        if (tag.matches("h[1-6]")) {
            int level = Character.digit(tag.charAt(1), 10);
            result.append("#".repeat(level)).append(' ').append(element.text()).append("\n\n");
            return;
        }
        if ("pre".equals(tag)) {
            result.append("```\n").append(element.text()).append("\n```\n\n");
            return;
        }
        if ("li".equals(tag)) {
            result.append("- ").append(element.text()).append('\n');
            return;
        }
        if ("ul".equals(tag) || "ol".equals(tag)) {
            for (Element item : element.children()) {
                if ("li".equalsIgnoreCase(item.tagName())) {
                    appendBlock(result, item);
                }
            }
            result.append('\n');
            return;
        }
        result.append(element.text()).append("\n\n");
    }
}
