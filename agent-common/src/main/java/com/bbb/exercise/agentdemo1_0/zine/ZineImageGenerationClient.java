package com.bbb.exercise.agentdemo1_0.zine;
import reactor.core.publisher.Mono;
public interface ZineImageGenerationClient { Mono<ZineImageResult> generate(String imageDataUrl,String prompt,String apiKey); record ZineImageResult(String imageUrl,String providerRequestId){} }
