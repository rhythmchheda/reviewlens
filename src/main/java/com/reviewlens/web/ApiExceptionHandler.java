package com.reviewlens.web;

import com.reviewlens.llm.LlmException;
import com.reviewlens.summary.InvalidSummaryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Model answered twice with unusable output. Still an upstream failure: 502. */
    @ExceptionHandler(InvalidSummaryException.class)
    public ProblemDetail invalidOutput(InvalidSummaryException e) {
        log.warn("LLM returned invalid output after repair: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Summary provider returned invalid output");
    }

    /** Upstream LLM failed: 502 Bad Gateway, without leaking provider error details to clients. */
    @ExceptionHandler(LlmException.class)
    public ProblemDetail llmFailed(LlmException e) {
        log.warn("LLM call failed: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Summary provider unavailable, try again later");
    }
}
