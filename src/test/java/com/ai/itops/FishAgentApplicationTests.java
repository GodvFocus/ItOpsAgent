package com.ai.itops;

import io.milvus.client.MilvusServiceClient;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key"
})
class FishAgentApplicationTests {

    @MockBean
    MilvusServiceClient milvusServiceClient;

    @MockBean
    MilvusClientV2 milvusClientV2;

    @Test
    void contextLoads() {
    }

}
