package com.reviewlens.summary;

import com.reviewlens.llm.LlmException;

/** The model answered, but not in the required JSON shape. */
public class InvalidSummaryException extends LlmException {
    public InvalidSummaryException(String message) {
        super(message);
    }
}
