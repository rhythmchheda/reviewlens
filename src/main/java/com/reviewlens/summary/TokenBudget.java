package com.reviewlens.summary;

/** Cheap token estimate (~4 chars per token for English). Good enough for budgeting; not billing-accurate. */
public final class TokenBudget {

    private TokenBudget() {
    }

    public static int estimate(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
