package com.ai.itops;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({
        "com.ai.itops.auth.mapper",
        "com.ai.itops.chat.mapper",
        "com.ai.itops.rag.mapper",
        "com.ai.itops.card.mapper",
        "com.ai.itops.security.permission.mapper"
})
public class ItopsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItopsAgentApplication.class, args);
    }

}
