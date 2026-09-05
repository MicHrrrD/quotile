package dev.mich.quotile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads only the primary Codex quota bucket. It does not infer another ChatGPT product's quota.
 * Source: openai/codex rust-v0.153.4, codex-rs/backend-client/src/client.rs,
 * rate_limit_snapshots_from_payload / map_rate_limit_window / window_minutes_from_seconds.
 * Reset count: backend-client/src/types.rs RateLimitResetCreditsSummary.available_count.
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
        // This optional server summary is authoritative: detail lists may be truncated and
        // total_earned_count / credits.balance describe different quantities.
        JSONObject resetCredits = payload.optJSONObject("rate_limit_reset_credits");
        Long availableResetCount = optionalNonNegativeInteger(
                resetCredits == null ? null : resetCredits.opt("available_count"));
        return new JSONObject().put("schemaVersion", 1).put("plan", plan == null ? JSONObject.NULL : plan)
                .put("weekly", weekly == null ? JSONObject.NULL : weekly)
                .put("fiveHour", fiveHour == null ? JSONObject.NULL : fiveHour)
                .put("availableResetCount", availableResetCount == null ? JSONObject.NULL : availableResetCount)
                .put("nextResetCreditExpiresAt", JSONObject.NULL)
                .put("updatedAt", now).put("stale", false).put("error", JSONObject.NULL);
    }

    /**
     * Reset-credit detail lists can be truncated. Only a complete, unique set matching the
     * authoritative usage count can establish the nearest expiry; never infer a count here.
     * Source: backend-client/src/types.rs RateLimitResetCreditsDetails / RateLimitResetCreditDetails.
     */
    static Long nextResetCreditExpiry(JSONObject payload, Long expectedCount, long now) {
        if (payload == null || expectedCount == null || expectedCount <= 0) return null;
        Long count = optionalNonNegativeInteger(payload.opt("available_count"));
        JSONArray credits = payload.optJSONArray("credits");
        if (!expectedCount.equals(count) || credits == null || count > credits.length()) return null;
        Set<String> ids = new HashSet<>();
        long available = 0;
        Long nearest = null;
        for (int index = 0; index < credits.length(); index++) {
            JSONObject credit = credits.optJSONObject(index);
            if (credit == null) return null;
            String id = detailString(credit.opt("id"), 4096);
            String status = detailString(credit.opt("status"), 128);
            String type = detailString(credit.opt("reset_type"), 128);
            if (id == null || status == null || type == null || !ids.add(id)) return null;
            if (!"available".equals(status) || !"codex_rate_limits".equals(type)) continue;
            // Absence is not evidence of an unlimited lifetime; explicit null is accepted.
            if (!credit.has("expires_at")) return null;
            Object rawExpiry = credit.opt("expires_at");
            if (rawExpiry == JSONObject.NULL) { available++; continue; }
            if (!(rawExpiry instanceof String) || ((String) rawExpiry).length() > 80) return null;
            long expires;
            try { expires = OffsetDateTime.parse((String) rawExpiry).toEpochSecond(); }
            catch (RuntimeException invalid) { return null; }
            if (expires < 0 || expires > 4102444800L) return null;
            if (expires <= now) continue;
            available++;
            if (nearest == null || expires < nearest) nearest = expires;
        }
        return available == count ? nearest : null;
    }

    private static String detailString(Object raw, int maximum) {
        if (!(raw instanceof String)) return null;
        String text = (String) raw;
        if (text.isEmpty() || text.length() > maximum) return null;
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return null;
        return text;
    }

    /** Optional counts must not coerce strings, floating point values, or overflowing numbers. */
    static Long optionalNonNegativeInteger(Object raw) {
        if (!(raw instanceof Integer) && !(raw instanceof Long)) return null;
        long value = ((Number) raw).longValue();
        return value >= 0 ? value : null;
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
