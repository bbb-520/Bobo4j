package com.bbb.exercise.agentdemo1_0.model;
import java.util.Locale;
public enum ModelProvider { GPT,GEMINI,QWEN,GLM,HY; public static ModelProvider parse(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("模型 Provider 不能为空");String n=value.trim().toUpperCase(Locale.ROOT);return switch(n){case "OPENAI"->GPT;case "GOOGLE"->GEMINI;case "DASHSCOPE"->QWEN;case "HUNYUAN","TENCENT"->HY;default->valueOf(n);};} }
