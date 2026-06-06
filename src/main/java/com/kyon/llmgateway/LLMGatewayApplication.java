package com.kyon.llmgateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LLMGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LLMGatewayApplication.class, args);
    }

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public CommandLineRunner printStartupMessage() {
        return s -> {
            System.out.println("""
                    ██   ██ ██    ██  ██████  ███    ██
                    ██  ██   ██  ██  ██    ██ ████   ██
                    █████     ████   ██    ██ ██ ██  ██
                    ██  ██     ██    ██    ██ ██  ██ ██
                    ██   ██    ██     ██████  ██   ████
                    """);
            System.out.println("========================================");
            System.out.println("  🚀 LLM Gateway 已成功启动");
            System.out.println("  🌐 访问地址: http://localhost:" + serverPort);
            System.out.println("========================================");
        };
    }

}
