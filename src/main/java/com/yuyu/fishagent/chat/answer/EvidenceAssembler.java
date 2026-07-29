package com.yuyu.fishagent.chat.answer;

import com.yuyu.fishagent.chat.dto.SourceRef;
import com.yuyu.fishagent.chat.dto.StructuredAnswer;
import com.yuyu.fishagent.common.resilience.CircuitBreakerHelper;
import com.yuyu.fishagent.common.resilience.ResilienceConstants;
import com.yuyu.fishagent.common.trace.PromptTraceSupport;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一证据装配器。
 * <p>负责三件事：分配 Evidence ID、约束模型输出只能引用这些 ID、把结构化对象渲染为最终展示文本。</p>
 */
@Slf4j
@Component
public class EvidenceAssembler {

    private final ChatModel chatModel;
    private final StructuredAnswerPromptBuilder promptBuilder;
    private final StructuredAnswerParser parser;
    private final StructuredAnswerRenderer renderer;
    private final PromptTraceSupport promptTraceSupport;
    private final CircuitBreakerHelper circuitBreakerHelper;

    public EvidenceAssembler(@Qualifier("memoryChatModel") ChatModel chatModel,
                             StructuredAnswerPromptBuilder promptBuilder,
                             StructuredAnswerParser parser,
                             StructuredAnswerRenderer renderer,
                             PromptTraceSupport promptTraceSupport,
                             CircuitBreakerHelper circuitBreakerHelper) {
        this.chatModel = chatModel;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.renderer = renderer;
        this.promptTraceSupport = promptTraceSupport;
        this.circuitBreakerHelper = circuitBreakerHelper;
    }

    public StructuredAnswer assemble(String userInput, String finalAnswer, List<RagRecall.RecallHit> hits) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return null;
        }
        List<SourceRef> evidences = SourceRef.from(hits);
        StructuredAnswer answer = tryModelStructuredAnswer(userInput, finalAnswer, evidences);
        if (answer == null) {
            answer = fallback(finalAnswer, evidences);
        }
        return new StructuredAnswer(
                answer.judgement(),
                answer.possibleCauses(),
                answer.steps(),
                answer.riskWarnings(),
                answer.missingInformation(),
                answer.evidences(),
                renderer.render(answer)
        );
    }

    private StructuredAnswer tryModelStructuredAnswer(String userInput, String finalAnswer, List<SourceRef> evidences) {
        try {
            Prompt prompt = promptBuilder.build(userInput, finalAnswer, evidences);
            promptTraceSupport.recordCurrentTurnPrompt("evidence-assembler", prompt);
            String raw = circuitBreakerHelper.executeWithCircuitBreaker(
                    ResilienceConstants.CB_LLM,
                    () -> chatModel.call(prompt)).getResult().getOutput().getText();
            return parser.parse(raw, evidences);
        } catch (Exception e) {
            log.warn("[EvidenceAssembler] 结构化整理失败，回退规则装配: {}", e.getMessage());
            return null;
        }
    }

    private StructuredAnswer fallback(String finalAnswer, List<SourceRef> evidences) {
        List<String> judgementEvidenceIds = evidences.stream()
                .map(SourceRef::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .limit(3)
                .toList();
        List<String> missingInformation = evidences.isEmpty()
                ? List.of("当前回答没有可绑定证据，请补充日志、报错信息或相关文档。")
                : List.of();
        return new StructuredAnswer(
                new StructuredAnswer.Judgement(finalAnswer.trim(), judgementEvidenceIds),
                List.of(),
                List.of(),
                List.of(),
                missingInformation,
                evidences,
                ""
        );
    }
}
