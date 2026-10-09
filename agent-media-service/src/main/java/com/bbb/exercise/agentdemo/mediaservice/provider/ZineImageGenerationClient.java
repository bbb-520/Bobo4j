package com.bbb.exercise.agentdemo.mediaservice.provider;
import reactor.core.publisher.Mono;
public interface ZineImageGenerationClient { Mono<ZineImageResult> generate(String imageDataUrl,String prompt,String apiKey); record ZineImageResult(String imageUrl,String providerRequestId,Long inputTokens,Long outputTokens){public ZineImageResult(String url,String id){this(url,id,null,null);}} }
