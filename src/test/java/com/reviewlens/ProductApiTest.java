package com.reviewlens;

import com.reviewlens.llm.LlmException;
import com.reviewlens.llm.LlmProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fast retries, and a breaker that never opens, so failure tests cannot affect each other. */
@SpringBootTest(properties = {
        "reviewlens.llm.retry.initial-backoff-ms=1",
        "reviewlens.llm.circuit.failure-threshold=1000"})
@AutoConfigureMockMvc
class ProductApiTest {

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    LlmProvider llm;

    long createProduct(String name) throws Exception {
        String body = mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    void addReview(long id, int rating, String author, String text) throws Exception {
        mvc.perform(post("/products/" + id + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + ",\"author\":\"" + author + "\",\"text\":\"" + text + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listsSeededProducts() throws Exception {
        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Wireless Earbuds X1"));
    }

    @Test
    void seededProductFiltersDuplicateSpamAndInjection() throws Exception {
        mvc.perform(get("/products/1/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawStats.count").value(7))
                .andExpect(jsonPath("$.rawStats.averageRating").value(3.57))
                .andExpect(jsonPath("$.stats.count").value(4))
                .andExpect(jsonPath("$.stats.averageRating").value(3.5))
                .andExpect(jsonPath("$.filtering.counts.duplicate").value(1))
                .andExpect(jsonPath("$.filtering.counts.spam").value(1))
                .andExpect(jsonPath("$.filtering.counts.injection").value(1))
                .andExpect(jsonPath("$.grounding.quotesDropped").value(1))
                .andExpect(jsonPath("$.summary.sentiment").value("mixed"))
                .andExpect(jsonPath("$.provider").value("mock"));
    }

    @Test
    void largeProductUsesMapReduce() throws Exception {
        mvc.perform(get("/products/3/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.count").value(24))
                .andExpect(jsonPath("$.usage.chunks").value(3))
                .andExpect(jsonPath("$.usage.llmCalls").value(4));
    }

    @Test
    void createProductAndReviewThenSummarize() throws Exception {
        long id = createProduct("Test Lamp");
        addReview(id, 4, "t", "Bright and sturdy");
        addReview(id, 2, "u", "Switch broke");

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.count").value(2))
                .andExpect(jsonPath("$.stats.averageRating").value(3.0))
                .andExpect(jsonPath("$.summary.pros[0]").value("Bright and sturdy"))
                .andExpect(jsonPath("$.summary.cons[0]").value("Switch broke"));
    }

    @Test
    void emptyProductDoesNotCallLlm() throws Exception {
        long id = createProduct("Empty");
        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.usage.llmCalls").value(0));
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void rejectsInvalidRating() throws Exception {
        mvc.perform(post("/products/1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9,\"author\":\"x\",\"text\":\"y\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/products/9999/summary")).andExpect(status().isNotFound());
    }

    @Test
    void llmFailureIs502() throws Exception {
        long id = createProduct("Flaky");
        addReview(id, 5, "a", "Works well");
        doThrow(new LlmException("boom")).when(llm).complete(anyString(), anyString());

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Summary provider unavailable, try again later"));
    }

    @Test
    void invalidLlmOutputIs502AfterRepair() throws Exception {
        long id = createProduct("Chatty");
        addReview(id, 5, "a", "Works well");
        org.mockito.Mockito.doReturn(new com.reviewlens.llm.LlmResponse("I think it's great!", 10, 5))
                .when(llm).complete(anyString(), anyString());

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Summary provider returned invalid output"));
        verify(llm, times(2)).complete(anyString(), anyString()); // original + one repair
    }

    @Test
    void retriesBeforeReturning502() throws Exception {
        long id = createProduct("Retried");
        addReview(id, 5, "a", "Works well");
        doThrow(new LlmException("boom")).when(llm).complete(anyString(), anyString());

        mvc.perform(get("/products/" + id + "/summary")).andExpect(status().isBadGateway());
        verify(llm, times(3)).complete(anyString(), anyString()); // max-attempts
    }

    @Test
    void secondCallIsServedFromCache() throws Exception {
        long id = createProduct("Cached");
        addReview(id, 5, "a", "Works well");

        mvc.perform(get("/products/" + id + "/summary")).andExpect(jsonPath("$.cached").value(false));
        mvc.perform(get("/products/" + id + "/summary")).andExpect(jsonPath("$.cached").value(true));
        verify(llm, times(1)).complete(anyString(), anyString());
    }

    @Test
    void newReviewInvalidatesCache() throws Exception {
        long id = createProduct("Invalidated");
        addReview(id, 5, "a", "Works well");
        mvc.perform(get("/products/" + id + "/summary"));

        addReview(id, 1, "b", "Broke on day one");
        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.stats.count").value(2));
    }

    @Test
    void refreshBypassesCache() throws Exception {
        long id = createProduct("Refreshed");
        addReview(id, 5, "a", "Works well");
        mvc.perform(get("/products/" + id + "/summary"));
        mvc.perform(get("/products/" + id + "/summary?refresh=true")).andExpect(jsonPath("$.cached").value(false));
        verify(llm, times(2)).complete(anyString(), anyString());
    }

    @Test
    void servesStaleSummaryWhenLlmIsDown() throws Exception {
        long id = createProduct("Degraded");
        addReview(id, 5, "a", "Works well");
        mvc.perform(get("/products/" + id + "/summary")).andExpect(status().isOk());

        addReview(id, 2, "b", "Stopped working");
        doThrow(new LlmException("provider down")).when(llm).complete(anyString(), anyString());

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.stats.count").value(1)); // the old summary, honestly labeled
    }

    @Test
    void exposesHealthAndMetrics() throws Exception {
        mvc.perform(get("/products/1/summary"));
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.llmCircuit.details.circuit").value("CLOSED"));
        mvc.perform(get("/actuator/metrics/reviewlens.llm.calls")).andExpect(status().isOk());
    }
}
