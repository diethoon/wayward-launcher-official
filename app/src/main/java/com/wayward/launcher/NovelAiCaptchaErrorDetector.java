package com.wayward.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/** Recognizes only NovelAI's free-trial reCAPTCHA-required failure in image API responses. */
final class NovelAiCaptchaErrorDetector {
    private static final String REQUIRED_MESSAGE =
            "recaptcha token is required for trial generation";
    private static final String ERROR_TYPE = "novelai_trial_recaptcha_required";

    private NovelAiCaptchaErrorDetector() {}

    static String detect(String apiPath, byte[] responseBody) {
        if (apiPath == null || !("/api/image/generate".equals(apiPath)
                || "/api/image/generate/".equals(apiPath)
                || apiPath.startsWith("/api/image/status/"))) return null;
        if (responseBody == null || responseBody.length == 0) return null;
        String raw = new String(responseBody, StandardCharsets.UTF_8);
        try {
            return findSignal(new JSONObject(raw), 0);
        } catch (Exception ignored) {
            return raw.trim().equalsIgnoreCase(REQUIRED_MESSAGE) ? ERROR_TYPE : null;
        }
    }

    private static String findSignal(JSONObject object, int depth) {
        if (object == null || depth > 6) return null;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (value instanceof String) {
                String text = ((String) value).toLowerCase(Locale.ROOT);
                if ((normalizedKey.contains("error") || normalizedKey.contains("message")
                        || normalizedKey.contains("detail") || normalizedKey.equals("code")
                        || normalizedKey.equals("type"))
                        && (text.contains(REQUIRED_MESSAGE) || text.equals(ERROR_TYPE)
                        || text.equals("trial_recaptcha_required"))) return ERROR_TYPE;
            } else if (value instanceof JSONObject) {
                String nested = findSignal((JSONObject) value, depth + 1);
                if (nested != null) return nested;
            } else if (value instanceof JSONArray
                    && (normalizedKey.contains("error") || normalizedKey.contains("message")
                    || normalizedKey.contains("detail"))) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof JSONObject) {
                        String nested = findSignal((JSONObject) item, depth + 1);
                        if (nested != null) return nested;
                    } else if (item instanceof String
                            && ((String) item).toLowerCase(Locale.ROOT).contains(REQUIRED_MESSAGE)) {
                        return ERROR_TYPE;
                    }
                }
            }
        }
        return null;
    }
}
