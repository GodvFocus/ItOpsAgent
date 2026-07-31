package com.ai.itops;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BackendPackageRefactorTest {

    private static final List<String> EXPECTED_CLASSES = List.of(
            "com.ai.itops.common.exception.GlobalExceptionHandler",
            "com.ai.itops.common.exception.SessionLockedException",
            "com.ai.itops.common.ratelimit.RateLimitResult",
            "com.ai.itops.common.ratelimit.RateLimitService",
            "com.ai.itops.common.config.WebMvcConfig",
            "com.ai.itops.common.config.SchedulingConfiguration",
            "com.ai.itops.common.config.RateLimitProperties",
            "com.ai.itops.common.dto.ChatMessageDTO",
            "com.ai.itops.auth.AuthController",
            "com.ai.itops.auth.AuthService",
            "com.ai.itops.auth.dto.LoginRequest",
            "com.ai.itops.auth.dto.LoginResponse",
            "com.ai.itops.auth.dto.RegisterRequest",
            "com.ai.itops.auth.entity.SysUser",
            "com.ai.itops.auth.mapper.SysUserMapper",
            "com.ai.itops.auth.enums.UserRole",
            "com.ai.itops.auth.config.AuthProperties",
            "com.ai.itops.llm.config.FishChatModelConfiguration",
            "com.ai.itops.llm.config.FishEmbeddingModelConfiguration",
            "com.ai.itops.llm.config.FishLlmChatProvider",
            "com.ai.itops.llm.config.FishLlmConfigurationConsistencyLogger",
            "com.ai.itops.llm.config.FishLlmEmbeddingProperties",
            "com.ai.itops.llm.config.FishLlmEnvironmentPostProcessor",
            "com.ai.itops.llm.config.FishLlmProperties",
            "com.ai.itops.memory.LongTermMemoryIngestionService",
            "com.ai.itops.memory.MemoryCompressionService",
            "com.ai.itops.memory.shortterm.RedisShortTermMemoryStore",
            "com.ai.itops.memory.shortterm.ShortTermMemorySnapshot",
            "com.ai.itops.memory.shortterm.ShortTermMemoryStore",
            "com.ai.itops.memory.longterm.MilvusLongTermMemoryStore",
            "com.ai.itops.memory.longterm.LongTermMemoryFactSanitizer",
            "com.ai.itops.memory.longterm.LongTermMemoryPromptBuilder",
            "com.ai.itops.memory.longterm.LongTermMemoryResponseParser",
            "com.ai.itops.memory.longterm.LongTermMemoryStore",
            "com.ai.itops.memory.compress.MemoryPromptBuilder",
            "com.ai.itops.memory.compress.MemoryResponseParser",
            "com.ai.itops.memory.config.MemoryProperties",
            "com.ai.itops.agent.config.AgentProperties",
            "com.ai.itops.agent.config.ToolProperties",
            "com.ai.itops.rag.KnowledgeController",
            "com.ai.itops.rag.service.KnowledgeIngestionService",
            "com.ai.itops.rag.service.KnowledgeManageService",
            "com.ai.itops.rag.service.MultipartInitResult",
            "com.ai.itops.rag.service.OrphanTaskCompensationService",
            "com.ai.itops.rag.service.RustFsService",
            "com.ai.itops.rag.pipeline.expand.RagQueryExpand",
            "com.ai.itops.rag.pipeline.expand.RagQueryExpandConfiguration",
            "com.ai.itops.rag.pipeline.query.RagQueryRewrite",
            "com.ai.itops.rag.pipeline.query.RagQueryRewriteConfiguration",
            "com.ai.itops.rag.pipeline.recall.RagRecall",
            "com.ai.itops.rag.pipeline.recall.RagRecallConfiguration",
            "com.ai.itops.rag.pipeline.recall.PublicKnowledgeMilvusSearcher",
            "com.ai.itops.rag.pipeline.recall.UserKnowledgeMilvusSearcher",
            "com.ai.itops.rag.pipeline.recall.UserMemoryMilvusSearcher",
            "com.ai.itops.rag.dto.DocumentMetadataPageResponse",
            "com.ai.itops.rag.dto.DocumentMetadataResponse",
            "com.ai.itops.rag.dto.DocumentTaskStatusResponse",
            "com.ai.itops.rag.dto.KnowledgeUploadResponse",
            "com.ai.itops.rag.dto.MultipartAbortRequest",
            "com.ai.itops.rag.dto.MultipartCompleteRequest",
            "com.ai.itops.rag.dto.MultipartInitRequest",
            "com.ai.itops.rag.dto.MultipartInitResponse",
            "com.ai.itops.rag.dto.MultipartPartInfo",
            "com.ai.itops.rag.dto.MultipartPartResponse",
            "com.ai.itops.rag.entity.DocumentMetadata",
            "com.ai.itops.rag.mapper.DocumentMetadataMapper",
            "com.ai.itops.rag.config.KnowledgeProperties",
            "com.ai.itops.rag.config.RagProperties",
            "com.ai.itops.rag.config.RustFsProperties",
            "com.ai.itops.chat.ChatController",
            "com.ai.itops.chat.ChatService",
            "com.ai.itops.chat.ChatMetadataService",
            "com.ai.itops.chat.history.ChatMemoryStore",
            "com.ai.itops.chat.history.RustFsChatMemoryStore",
            "com.ai.itops.chat.history.UserScopedFileChatMemoryStore",
            "com.ai.itops.chat.dto.ChatRequest",
            "com.ai.itops.chat.dto.SessionInfo",
            "com.ai.itops.chat.entity.ChatMetadata",
            "com.ai.itops.chat.mapper.ChatMetadataMapper"
    );

    private static final List<String> LEGACY_CLASSES = List.of(
            "com.ai.itops.exception.GlobalExceptionHandler",
            "com.ai.itops.ratelimit.RateLimitService",
            "com.ai.itops.controller.ChatController",
            "com.ai.itops.service.ChatService",
            "com.ai.itops.dto.ChatRequest",
            "com.ai.itops.entity.ChatMetadata",
            "com.ai.itops.mapper.ChatMetadataMapper",
            "com.ai.itops.config.AgentProperties",
            "com.ai.itops.config.llm.FishLlmProperties",
            "com.ai.itops.agent.memory.longterm.PublicKnowledgeDocument",
            "com.ai.itops.agent.memory.rag.recall.RagRecall"
    );

    @TestFactory
    Stream<DynamicTest> expectedPackagesExposeMovedClasses() {
        return EXPECTED_CLASSES.stream()
                .map(className -> DynamicTest.dynamicTest(className,
                        () -> assertDoesNotThrow(() -> Class.forName(className))));
    }

    @TestFactory
    Stream<DynamicTest> legacyPackagesNoLongerExposeMovedClasses() {
        return LEGACY_CLASSES.stream()
                .map(className -> DynamicTest.dynamicTest(className,
                        () -> assertThrows(ClassNotFoundException.class, () -> Class.forName(className))));
    }
}
