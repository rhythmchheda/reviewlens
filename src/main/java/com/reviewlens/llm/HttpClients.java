package com.reviewlens.llm;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

final class HttpClients {

    private HttpClients() {
    }

    /** Never call an LLM without timeouts: a hung connection would hold a request thread forever. */
    static SimpleClientHttpRequestFactory withTimeouts(int readTimeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }
}
