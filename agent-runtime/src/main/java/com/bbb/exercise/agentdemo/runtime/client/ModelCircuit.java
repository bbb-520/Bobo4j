package com.bbb.exercise.agentdemo.runtime.client;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;
/** Redis window/open/probe keys use TTLs; local finite circuit remains available during Redis outages. */
@Component
public class ModelCircuit {
    private final ConcurrentHashMap<String,State> circuits=new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    public ModelCircuit(){this.redis=null;}
    @Autowired public ModelCircuit(org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis){this.redis=redis.getIfAvailable();}
    public boolean acquire(AuthModelClient.SelectedModel model) {
        if(redis!=null)try {Long permit=redis.execute(new DefaultRedisScript<>("if redis.call('EXISTS',KEYS[1])==1 then return 0 end if redis.call('EXISTS',KEYS[2])==1 then if redis.call('SET',KEYS[3],'1','NX','EX',30) then return 1 else return 0 end end return 1",Long.class),redisKeys(model));return permit!=null&&permit==1;}catch(RuntimeException ignored){}
        var s=circuits.computeIfAbsent(key(model),k -> new State());synchronized(s){long now=System.currentTimeMillis();if(s.openUntil>now)return false;if(s.openUntil!=0){if(s.probe)return false;s.probe=true;}return true;}
    }
    public void success(AuthModelClient.SelectedModel model){if(redis!=null)try{redis.delete(redisKeys(model));redis.delete(redisKey(model)+":failures");}catch(RuntimeException ignored){}var s=circuits.get(key(model));if(s!=null)synchronized(s){s.failures.clear();s.openUntil=0;s.probe=false;}}
    public void failure(AuthModelClient.SelectedModel model){if(redis!=null)try{var keys=new java.util.ArrayList<>(redisKeys(model));keys.add(redisKey(model)+":failures");redis.execute(new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[4]); if n==1 then redis.call('EXPIRE',KEYS[4],60) end if n>=5 or redis.call('EXISTS',KEYS[3])==1 then redis.call('SET',KEYS[1],'1','EX',30);redis.call('SET',KEYS[2],'1','EX',90);redis.call('DEL',KEYS[3]); end return n",Long.class),keys);}catch(RuntimeException ignored){}var s=circuits.computeIfAbsent(key(model),k -> new State());synchronized(s){long now=System.currentTimeMillis();while(!s.failures.isEmpty()&&s.failures.peekFirst()<now-60000)s.failures.removeFirst();s.failures.addLast(now);if(s.probe||s.failures.size()>=5)s.openUntil=now+30000;s.probe=false;}}
    private static String key(AuthModelClient.SelectedModel m){return m.provider()+":"+m.model()+":"+m.baseUrl();}
    public double openCount(){long now=System.currentTimeMillis();return circuits.values().stream().filter(s -> s.openUntil>now).count();}
    private static String redisKey(AuthModelClient.SelectedModel model){return "ai:circuit:"+java.util.HexFormat.of().formatHex(java.util.UUID.nameUUIDFromBytes(key(model).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
    private static List<String> redisKeys(AuthModelClient.SelectedModel model){String key=redisKey(model);return List.of(key+":open",key+":state",key+":probe");}
    private static final class State {final ArrayDeque<Long> failures=new ArrayDeque<>();long openUntil;boolean probe;}
}
