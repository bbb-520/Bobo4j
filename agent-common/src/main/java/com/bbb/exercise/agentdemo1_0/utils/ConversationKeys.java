package com.bbb.exercise.agentdemo1_0.utils;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentity;
import org.springframework.util.StringUtils;
import java.util.Locale; import java.util.UUID; import java.util.regex.Pattern;
public final class ConversationKeys {
 private static final Pattern UUID_PATTERN=Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"); public static final String MEMORY_PREFIX="chat:"; private ConversationKeys(){}
 public static String newConversationId(){return UUID.randomUUID().toString();}
 public static String requireExistingOrNull(String id){if(!StringUtils.hasText(id))return null;if(!UUID_PATTERN.matcher(id).matches())throw new IllegalArgumentException("sessionId 必须是服务端返回的完整 UUID");return id.toLowerCase(Locale.ROOT);}
 public static String memoryKey(ChatIdentity i,String conversationId){return MEMORY_PREFIX+i.tenantId()+":"+i.userId()+":"+conversationId;}
}
