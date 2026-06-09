package com.kyon.llmgateway.agent.tool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ApplicationContext applicationContext;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        // 扫描所有带 @ToolDef 注解到 Bean
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(ToolDef.class);
        for (Object bean : beans.values()) {
            if (bean instanceof Tool tool) {
                tools.put(tool.getName(), tool);
                log.info("Registered tool: {} - {}", tool.getName(), tool.getDescription());
            }
        }
        log.info("ToolRegistry initialized with {} tools", tools.size());
    }

    public Tool getTool(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool;
    }

    public List<Tool> getAllTools() {
        return List.copyOf(tools.values());
    }

}
