package com.kyon.llmgateway.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为 Tool，启动时自动注册到 ToolRegistry
 */
@Target(ElementType.TYPE)  // 只能标记在类上
@Retention(RetentionPolicy.RUNTIME)  // 运行时可通过反射读取
public @interface ToolDef {
}
