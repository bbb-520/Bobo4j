package com.bbb.exercise.agentdemo1_0.health;
import org.springframework.web.bind.annotation.GetMapping; import org.springframework.web.bind.annotation.RestController; import reactor.core.publisher.Mono; import java.util.Map;
@RestController public class HealthController { @GetMapping("/health") public Mono<Map<String,String>> health(){return Mono.just(Map.of("status","ok"));} }
