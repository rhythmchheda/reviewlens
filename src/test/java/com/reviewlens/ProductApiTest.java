package com.reviewlens;

import com.reviewlens.llm.LlmException;
import com.reviewlens.llm.LlmProvider;
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
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductApiTest {

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    LlmProvider llm;

    @Test
    void listsSeededProducts() throws Exception {
        mvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Wireless Earbuds X1"));
    }

    @Test
    void createProductAndReviewThenSummarize() throws Exception {
        String body = mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Lamp\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/products/" + id + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"author\":\"t\",\"text\":\"Bright and sturdy\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/products/" + id + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2,\"author\":\"u\",\"text\":\"Switch broke\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.count").value(2))
                .andExpect(jsonPath("$.stats.averageRating").value(3.0))
                .andExpect(jsonPath("$.reviewsUsed").value(2))
                .andExpect(jsonPath("$.provider").value("mock"));
    }

    @Test
    void emptyProductDoesNotCallLlm() throws Exception {
        String body = mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Empty\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/products/" + id + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("No reviews yet."));
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
        doThrow(new LlmException("boom")).when(llm).complete(anyString(), anyString());
        mvc.perform(get("/products/1/summary"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Summary provider unavailable, try again later"));
    }
}
