package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.bbb.exercise.agentdemo.runtime.security.BrowserIdentityProvider;
import com.bbb.exercise.agentdemo.ragservice.web.RagExceptionHandler;
import java.util.*;
import static org.mockito.Mockito.*;

class GalleryControllerTest {
    private final PromptGalleryService service=mock(PromptGalleryService.class);
    private final GalleryController controller=new GalleryController(service,mock(GalleryPromptAssistant.class),new GalleryUsageGate(new GalleryProperties()));
    @Test void ordinaryLoggedInUserCannotTriggerPaidSearch() {
        client(Set.of("USER"),Set.of("rag:document:manage","model:manage")).post().uri("/api/documents/prompt-gallery/search").bodyValue(new GalleryController.SearchInput("tree",5,null)).exchange().expectStatus().isForbidden();
        verifyNoInteractions(service);
    }
    @Test void importDefaultsToDryRunAndAdministratorCanSearch() throws Exception {
        when(service.importConfiguredSource(true)).thenReturn(new PromptGalleryService.ImportResult(true,31,163,164,0,0,GallerySource.COMMIT,List.of()));
        client(Set.of("ADMIN"),Set.of("model:manage")).post().uri("/api/documents/prompt-gallery/import").exchange().expectStatus().isOk().expectBody().jsonPath("$.dryRun").isEqualTo(true).jsonPath("$.upserted").isEqualTo(0);
        verify(service).importConfiguredSource(true);
    }
    @Test void modelConfigurationErrorIsActionableEvenWithExistingGlobalAdvice() {
        when(service.searchPrompts("tree",5,null)).thenThrow(new GalleryNotReadyException("Prompt gallery model is not configured: set OPENAI_API_KEY"));
        client(Set.of("ADMIN"),Set.of("model:manage")).post().uri("/api/documents/prompt-gallery/search").bodyValue(new GalleryController.SearchInput("tree",5,null)).exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.message").isEqualTo("Prompt gallery model is not configured: set OPENAI_API_KEY");
    }
    private WebTestClient client(Set<String> roles,Set<String> permissions) {
        return WebTestClient.bindToController(controller).controllerAdvice(new RagExceptionHandler(),new GalleryExceptionHandler())
                .webFilter((exchange,chain)->{exchange.getAttributes().put(BrowserIdentityProvider.USER_ATTRIBUTE,new BrowserIdentityProvider.BrowserUser("operator","local",roles,permissions));return chain.filter(exchange);}).build();
    }
}
