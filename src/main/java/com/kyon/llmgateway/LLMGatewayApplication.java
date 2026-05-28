package com.kyon.llmgateway;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LLMGatewayApplication {

    static void main(String[] args) {
        SpringApplication.run(LLMGatewayApplication.class, args);
    }

    @Bean
    public CommandLineRunner printStartupMessage() {
        return _ -> {
            System.out.println("""
                    ██   ██ ██    ██  ██████  ███    ██
                    ██  ██   ██  ██  ██    ██ ████   ██
                    █████     ████   ██    ██ ██ ██  ██
                    ██  ██     ██    ██    ██ ██  ██ ██
                    ██   ██    ██     ██████  ██   ████
                    """);
            System.out.println("========================================");
            System.out.println("  🚀 LLM Gateway 已成功启动");
            System.out.println("  🌐 访问地址: http://localhost:8080");
            System.out.println("========================================");
        };
    }

}
