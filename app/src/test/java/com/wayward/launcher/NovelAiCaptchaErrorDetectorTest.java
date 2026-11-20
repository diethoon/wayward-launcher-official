package com.wayward.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class NovelAiCaptchaErrorDetectorTest {
    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test public void detectsExactTrialErrorOnGenerateResponse() {
        assertEquals("novelai_trial_recaptcha_required",
                NovelAiCaptchaErrorDetector.detect("/api/image/generate",
                        json("{\"error\":\"Recaptcha token is required for trial generation\"}")));
    }

    @Test public void detectsStructuredErrorTypeNestedInFailure() {
        assertEquals("novelai_trial_recaptcha_required",
                NovelAiCaptchaErrorDetector.detect("/api/image/generate",
                        json("{\"job\":{\"error\":{\"type\":\"novelai_trial_recaptcha_required\"}}}")));
    }

    @Test public void retainsStatusEndpointCompatibility() {
        assertEquals("novelai_trial_recaptcha_required",
                NovelAiCaptchaErrorDetector.detect("/api/image/status/abc123",
                        json("{\"job\":{\"error\":\"Recaptcha token is required for trial generation\"}}")));
    }

    @Test public void ignoresUnrelatedPathsAndErrors() {
        assertNull(NovelAiCaptchaErrorDetector.detect("/api/image/health",
                json("{\"error\":\"Recaptcha token is required for trial generation\"}")));
        assertNull(NovelAiCaptchaErrorDetector.detect("/api/image/generate",
                json("{\"error\":\"invalid prompt\"}")));
    }

    @Test public void doesNotTreatPromptEchoAsAnError() {
        assertNull(NovelAiCaptchaErrorDetector.detect("/api/image/generate",
                json("{\"prompt\":\"Recaptcha token is required for trial generation\","
                        + "\"status\":\"queued\"}")));
    }

    @Test public void ignoresEmptyAndMalformedResponses() {
        assertNull(NovelAiCaptchaErrorDetector.detect("/api/image/generate", new byte[0]));
        assertNull(NovelAiCaptchaErrorDetector.detect("/api/image/generate", json("not-json")));
    }
}
