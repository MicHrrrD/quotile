package dev.mich.quotile;

import org.json.JSONObject;

/**
 * Reads only the primary Codex quota bucket. It does not infer another ChatGPT product's quota.
 * Source: openai/codex rust-v0.153.4, codex-rs/backend-client/src/client.rs,
 * rate_limit_snapshots_from_payload / map_rate_limit_window / window_minutes_from_seconds.
 */
public final class RateLimitParser {
    private RateLimitParser() {}

    public static JSONObject parse(JSONObject payload, long now) throws Exception {
        Object plan = payload.opt("plan_type");
        if (plan != null && plan != JSONObject.NULL && !(plan instanceof String)) throw new IllegalArgumentException("plan_type");
        if (plan instanceof String && (((String) plan).length() > 80 || ((String) plan).matches(".*[\\p{Cntrl}].*")))
            throw new IllegalArgumentException("plan_type");
        JSONObject details = nullableObject(payload, "rate_limit");
        JSONObject weekly = null, fiveHour = null;
        if (details != null) {
            for (String name : new String[]{"primary_window", "secondary_window"}) {
                JSONObject window = nullableObject(details, name);
                if (window == null) continue;
                long seconds = integer(window, "limit_window_seconds");
                // Match the upstream ceil(seconds / 60) conversion before selecting a window.
                long minutes = seconds > 0 && seconds <= Integer.MAX_VALUE ? (seconds + 59) / 60 : 0;
                if (minutes != 10080 && minutes != 300) continue;
                Object raw = window.get("used_percent");
                if (!(raw instanceof Number)) throw new IllegalArgumentException("used_percent");
                double used = ((Number) raw).doubleValue();
                if (!Double.isFinite(used) || used < 0 || used > 100) throw new IllegalArgumentException("used_percent");
                long reset = integer(window, "reset_at");
                if (reset < 0 || reset > 4102444800L) throw new IllegalArgumentException("reset_at");
                JSONObject mapped = new JSONObject().put("remainingPercent", 100.0 - used).put("resetsAt", reset);
                if (minutes == 10080) {
                    if (weekly != null) throw new IllegalArgumentException("duplicate_weekly_window");
                    weekly = mapped;
                } else {
                    if (fiveHour != null) throw new IllegalArgumentException("duplicate_five_hour_window");
                    fiveHour = mapped;
                }
            }
        }
        if (weekly == null && fiveHour == null) throw new AccountClient.AccountException("quota_window_unavailable");
        return new JSONObject().put("schemaVersion", 1).put("plan", plan == null ? JSONObject.NULL : plan)
                .put("weekly", weekly == null ? JSONObject.NULL : weekly)
                .put("fiveHour", fiveHour == null ? JSONObject.NULL : fiveHour)
                .put("updatedAt", now).put("stale", false).put("error", JSONObject.NULL);
    }

    private static JSONObject nullableObject(JSONObject parent, String name) throws Exception {
        if (!parent.has(name) || parent.isNull(name)) return null;
        Object value = parent.get(name);
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException(name);
        return (JSONObject) value;
    }

    private static long integer(JSONObject object, String name) throws Exception {
        Object raw = object.get(name);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IllegalArgumentException(name);
        return ((Number) raw).longValue();
    }
}
