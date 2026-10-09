package com.bbb.exercise.agentdemo.ragservice.domain;
import com.google.gson.*;
import java.util.*;
/** Stable prompt and parameter bytes across process restarts (immutable Map iteration is randomized). */
public final class CanonicalJson {
    private static final Gson JSON=new Gson();private CanonicalJson(){}
    public static String write(Object value){return JSON.toJson(sort(JSON.toJsonTree(value)));}
    private static JsonElement sort(JsonElement value){if(value.isJsonObject()){var result=new JsonObject();value.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add(e.getKey(),sort(e.getValue())));return result;}if(value.isJsonArray()){var result=new JsonArray();for(var item:value.getAsJsonArray())result.add(sort(item));return result;}return value;}
}
