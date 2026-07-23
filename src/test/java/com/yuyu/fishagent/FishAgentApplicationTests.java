package com.yuyu.fishagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key"
})
class FishAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
