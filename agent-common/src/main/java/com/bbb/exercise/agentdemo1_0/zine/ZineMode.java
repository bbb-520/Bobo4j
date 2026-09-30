package com.bbb.exercise.agentdemo1_0.zine;
import java.util.Locale;
public enum ZineMode { GATHERED,DISTILLATION; public static ZineMode parse(String v){if(v==null||v.isBlank())return GATHERED;return switch(v.trim().toLowerCase(Locale.ROOT)){case "gathered","scenes-gathered-zine-v1-3","实景拼贴"->GATHERED;case "distillation","scene-distillation-zine-v1-3","影像蒸馏"->DISTILLATION;default->throw new IllegalArgumentException("mode 只能是 gathered 或 distillation");};} }
