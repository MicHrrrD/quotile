package dev.mich.quotile;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import org.json.JSONArray;
import org.json.JSONObject;

/** Security and data-contract tests run locally on Android with synthetic data only. */
final class AccountContractTests {
    interface Checked { void run() throws Exception; }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    private static void rejects(Checked action, String label) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        require(rejected, label);
    }
    private static JSONObject window(long seconds, Object used, long reset) throws Exception {
        return new JSONObject().put("limit_window_seconds",seconds).put("used_percent",used).put("reset_at",reset);
    }
    private static JSONObject payload(JSONObject first, JSONObject second) throws Exception {
        return new JSONObject().put("plan_type","pro").put("rate_limit",new JSONObject()
                .put("primary_window",first==null?JSONObject.NULL:first)
                .put("secondary_window",second==null?JSONObject.NULL:second));
    }
    static void run(Context app) throws Exception {
        long now=System.currentTimeMillis()/1000;
        JSONObject week=window(604800,25,now+86400), five=window(18000,70,now+3000);
        JSONObject mapped=RateLimitParser.parse(payload(five,week),now);
        require(mapped.getJSONObject("weekly").getDouble("remainingPercent")==75,"Weekly remaining");
        require(mapped.getJSONObject("fiveHour").getDouble("remainingPercent")==30,"5h remaining");
        mapped=RateLimitParser.parse(payload(week,five),now);
        require(mapped.getJSONObject("weekly").getLong("resetsAt")==now+86400,"Window order independent");
        require(RateLimitParser.parse(payload(week,null),now).isNull("fiveHour"),"Optional 5h stays unknown");
        require(RateLimitParser.parse(payload(week,window(300,10,now+100)),now).isNull("fiveHour"),"300 seconds is not 5h");
        rejects(()->RateLimitParser.parse(payload(week,week),now),"Duplicate weekly rejected");
        rejects(()->RateLimitParser.parse(payload(window(604800,-1,now),null),now),"Negative usage rejected");
        rejects(()->RateLimitParser.parse(payload(window(604800,101,now),null),now),"Over-100 usage rejected");
        rejects(()->RateLimitParser.parse(payload(window(604800,"25",now),null),now),"String usage rejected");
        rejects(()->RateLimitParser.parse(payload(null,null),now),"Missing quota not invented");
        resetCreditContract(app, week, five, now);
        resetExpiryContract(app, week, five, now);
        resetExpiryReadContract(app, week, five, now);

        String valid="GET /auth/callback?code=test-code&state=test-state HTTP/1.1\r\nHost: localhost:1455\r\n\r\n";
        require(parse(valid).get("state").equals("test-state"),"Valid loopback callback");
        rejects(()->parse(valid.replace("test-state HTTP","test-state&state=another HTTP")),"Duplicate state rejected");
        rejects(()->parse(valid.replace("\r\n\r\n","\r\nHost: evil.example\r\n\r\n")),"Duplicate Host rejected");
        rejects(()->parse(valid.replace("localhost:1455","evil.example")),"Host allowlist");
        rejects(()->parse(valid.replace("code=test-code","code=test-code&error=denied")),"Ambiguous error rejected");
        rejects(()->parse(valid.replace("GET /auth","GET http://localhost:1455/auth")),"Absolute target rejected");
        rejects(()->parse(valid.replace("/auth/callback","/auth/%63allback")),"Alternate encoded path rejected");

        AccountClient.LoginSession session=AccountClient.beginLogin(app);
        try {
            Uri url=Uri.parse(session.getAuthorizationUrl());
            require("https".equals(url.getScheme()) && "auth.openai.com".equals(url.getHost()),"Official login origin");
            require("S256".equals(url.getQueryParameter("code_challenge_method")),"PKCE S256");
            require(url.getQueryParameter("state").length()>=43,"Strong state");
            require(url.getQueryParameter("code_challenge").length()==43,"SHA256 challenge");
        } finally { session.close(); }
        require(!AccountClient.isSignedIn(app),"Preparing/cancelling login never fabricates credentials");

        TokenVault vault=new TokenVault(app);
        String marker="synthetic-access-token-for-local-test-only";
        synchronized(TokenVault.LOCK) {
            try {
                vault.write(new TokenVault.Credentials(marker,"synthetic-refresh","test-account","Local test",now+60));
                require(marker.equals(vault.read().accessToken),"Encrypted vault round trip");
                java.io.File file=new java.io.File(app.getNoBackupFilesDir(),"account-v1.bin");
                byte[] bytes=Files.readAllBytes(file.toPath());
                require(!new String(bytes,StandardCharsets.ISO_8859_1).contains(marker),"No plaintext token at rest");
                bytes[bytes.length-1]^=1;
                Files.write(file.toPath(),bytes);
                rejects(()->vault.read(),"Vault tampering rejected");
            } finally { vault.clear(); }
        }
        require(!AccountClient.isSignedIn(app),"Synthetic credentials cleaned up");
    }

    private static void resetCreditContract(Context app, JSONObject week, JSONObject five, long now) throws Exception {
        for (Object count : new Object[]{0, 2, Integer.MAX_VALUE, 3L, Long.MAX_VALUE}) {
            JSONObject response = payload(week, five).put("rate_limit_reset_credits",
                    new JSONObject().put("available_count", count));
            JSONObject snapshot = RateLimitParser.parse(response, now);
            require(snapshot.getLong("availableResetCount") == ((Number) count).longValue(),
                    "Available reset count preserves non-negative integers, including zero and i64 maximum");
            require(snapshot.getJSONObject("weekly").getDouble("remainingPercent") == 75,
                    "Reset credits do not change weekly quota");
        }
        Object[] invalidCounts = {JSONObject.NULL, -1, -1L, Long.MIN_VALUE, 1.0, 1.5, "2", true,
                new java.math.BigInteger("9223372036854775808"), new JSONObject()};
        for (Object count : invalidCounts) {
            JSONObject response = payload(week, five).put("rate_limit_reset_credits",
                    new JSONObject().put("available_count", count));
            JSONObject snapshot = RateLimitParser.parse(response, now);
            require(snapshot.isNull("availableResetCount"), "Malformed optional reset count is unknown");
            require(snapshot.getJSONObject("weekly").getDouble("remainingPercent") == 75,
                    "Malformed reset data must not invalidate a successful quota read");
        }
        JSONObject overflow = new JSONObject("{\"available_count\":9223372036854775808}");
        require(RateLimitParser.parse(payload(week, five).put("rate_limit_reset_credits", overflow), now)
                .isNull("availableResetCount"), "JSON integer overflow never saturates to a count");
        require(RateLimitParser.parse(payload(week, five), now).isNull("availableResetCount"),
                "Absent reset summary is unknown, not zero");
        for (Object summary : new Object[]{JSONObject.NULL, "unavailable", true, new JSONObject()}) {
            require(RateLimitParser.parse(payload(week, five).put("rate_limit_reset_credits", summary), now)
                    .isNull("availableResetCount"), "Absent count or invalid summary does not invalidate quota");
        }
        JSONObject independentAmounts = payload(week, five)
                .put("credits", new JSONObject().put("balance", "123.45").put("has_credits", true))
                .put("total_earned_count", 99)
                .put("rate_limit_reset_credits", new JSONObject().put("total_earned_count", 8)
                        .put("credits", new org.json.JSONArray().put(new JSONObject().put("status", "available"))));
        require(RateLimitParser.parse(independentAmounts, now).isNull("availableResetCount"),
                "Balance, earned total and available detail rows do not invent an available count");
        independentAmounts.getJSONObject("rate_limit_reset_credits").put("available_count", 2);
        require(RateLimitParser.parse(independentAmounts, now).getLong("availableResetCount") == 2,
                "Server summary is authoritative even when detail length and earned total differ");

        QuotaStore store = new QuotaStore(app);
        try {
            JSONObject snapshot = RateLimitParser.parse(independentAmounts, now);
            store.saveSnapshot(snapshot, store.generation());
            require(Long.valueOf(2).equals(new QuotaStore(app).state().availableResetCount),
                    "Available reset count survives a stored snapshot round trip");
            store.saveError("network_timeout", store.generation());
            WidgetState stale = new QuotaStore(app).state();
            require(stale.stale && Long.valueOf(2).equals(stale.availableResetCount),
                    "Failed refresh preserves the last known count as stale");
            snapshot.put("availableResetCount", 0L);
            store.saveSnapshot(snapshot, store.generation());
            require(Long.valueOf(0).equals(new QuotaStore(app).state().availableResetCount),
                    "Stored zero remains known, distinct from unknown");
            snapshot.put("availableResetCount", Long.MAX_VALUE);
            store.saveSnapshot(snapshot, store.generation());
            require(Long.valueOf(Long.MAX_VALUE).equals(new QuotaStore(app).state().availableResetCount),
                    "Stored i64 maximum remains exact");
            snapshot.remove("availableResetCount");
            store.saveSnapshot(snapshot, store.generation());
            WidgetState old = new QuotaStore(app).state();
            require(old.availableResetCount == null && Double.valueOf(75).equals(old.weeklyRemaining),
                    "Old schemaVersion 1 snapshots without reset counts remain compatible");
            for (Object count : invalidCounts) {
                snapshot.put("availableResetCount", count);
                store.saveSnapshot(snapshot, store.generation());
                WidgetState invalid = new QuotaStore(app).state();
                require(invalid.availableResetCount == null && Double.valueOf(75).equals(invalid.weeklyRemaining),
                        "Malformed stored optional reset count must not discard quota");
            }
            snapshot = RateLimitParser.parse(payload(week, five), now);
            store.saveSnapshot(snapshot, store.generation());
            require(new QuotaStore(app).state().availableResetCount == null,
                    "A new response with no reset count must not carry forward a previous count");
        } finally {
            store.clearSnapshot();
        }
        require(QuotaStore.demoState().availableResetCount.equals(2L), "Demo count is an explicit example");
    }

    private static JSONObject credit(String id, String status, String type, Object expiry) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("reset_type", type)
                .put("expires_at", expiry);
    }

    private static JSONObject details(Object count, JSONObject... credits) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject credit : credits) list.put(credit);
        return new JSONObject().put("available_count", count).put("credits", list);
    }

    private static void resetExpiryContract(Context app, JSONObject week, JSONObject five, long now) throws Exception {
        long soon = now + 3600, later = now + 7200;
        String soonIso = Instant.ofEpochSecond(soon).toString();
        String offsetIso = Instant.ofEpochSecond(later).atOffset(ZoneOffset.ofHours(8)).toString();
        JSONObject first = credit("synthetic-first", "available", "codex_rate_limits", soonIso);
        JSONObject second = credit("synthetic-second", "available", "codex_rate_limits", offsetIso);
        JSONObject unlimited = credit("synthetic-unlimited", "available", "codex_rate_limits", JSONObject.NULL);
        JSONObject used = credit("synthetic-used", "redeemed", "codex_rate_limits", Instant.ofEpochSecond(now + 20).toString());
        JSONObject other = credit("synthetic-other", "available", "other_limits", Instant.ofEpochSecond(now + 10).toString());
        JSONObject expired = credit("synthetic-expired", "available", "codex_rate_limits", Instant.ofEpochSecond(now - 60).toString());
        require(Long.valueOf(soon).equals(RateLimitParser.nextResetCreditExpiry(
                details(3, second, used, unlimited, other, first, expired), 3L, now)),
                "Nearest future expiry ignores redeemed, other-type, expired and unlimited entries");
        require(Long.valueOf(later).equals(RateLimitParser.nextResetCreditExpiry(details(1, second), 1L, now)),
                "Expiry offset is interpreted as an instant rather than device-local time");
        require(RateLimitParser.nextResetCreditExpiry(details(1, unlimited), 1L, now) == null,
                "Unlimited credit does not invent a date");
        require(RateLimitParser.nextResetCreditExpiry(details(1, expired), 1L, now) == null,
                "Already expired entries cannot establish a future date");
        require(RateLimitParser.nextResetCreditExpiry(details(2, first), 2L, now) == null,
                "Truncated available detail set cannot establish the true nearest expiry");
        require(RateLimitParser.nextResetCreditExpiry(details(2, first, second), 3L, now) == null,
                "A count changing between requests leaves expiry unknown");
        require(RateLimitParser.nextResetCreditExpiry(details(1, first, first), 1L, now) == null,
                "Duplicate IDs make completeness unverifiable");
        require(RateLimitParser.nextResetCreditExpiry(details(0, first), 0L, now) == null,
                "Zero count never exposes an expiry");
        require(RateLimitParser.nextResetCreditExpiry(details(1, first), null, now) == null,
                "Unknown usage count never inferred from details");
        for (Object bad : new Object[]{"not-a-date", "2026-02-30T00:00:00Z", "2026-09-05T10:00:00",
                "2101-01-01T00:00:00Z", "1960-01-01T00:00:00Z", 123, new JSONObject(), true}) {
            JSONObject malformed = credit("synthetic-bad", "available", "codex_rate_limits", bad);
            require(RateLimitParser.nextResetCreditExpiry(details(2, first, malformed), 2L, now) == null,
                    "Malformed available expiry cannot be skipped to claim completeness");
        }
        JSONObject missingDate = credit("synthetic-missing", "available", "codex_rate_limits", JSONObject.NULL);
        missingDate.remove("expires_at");
        require(RateLimitParser.nextResetCreditExpiry(details(2, first, missingDate), 2L, now) == null,
                "Absent expiry differs from an explicitly unlimited lifetime");
        JSONObject malformedList = details(1, first);
        malformedList.getJSONArray("credits").put("invalid-row");
        require(RateLimitParser.nextResetCreditExpiry(malformedList, 1L, now) == null,
                "Malformed list cannot establish completeness");
        for (Object badCount : new Object[]{JSONObject.NULL, -1, 1.0, "1", true}) {
            require(RateLimitParser.nextResetCreditExpiry(details(badCount, first), 1L, now) == null,
                    "Detail count uses the same strict integer contract as usage");
        }

        QuotaStore store = new QuotaStore(app);
        try {
            JSONObject snapshot = RateLimitParser.parse(payload(week, five).put("rate_limit_reset_credits",
                    new JSONObject().put("available_count", 2)), now);
            snapshot.put("nextResetCreditExpiresAt", soon);
            store.saveSnapshot(snapshot, store.generation());
            require(Long.valueOf(soon).equals(new QuotaStore(app).state().nextResetCreditExpiresAt),
                    "Expiry survives a stored snapshot round trip");
            store.saveError("network_timeout", store.generation());
            WidgetState stale = new QuotaStore(app).state();
            require(stale.stale && Long.valueOf(soon).equals(stale.nextResetCreditExpiresAt),
                    "A failed main refresh preserves the known expiry as stale");
            for (Object badExpiry : new Object[]{JSONObject.NULL, 0, -1, soon * 1.0, "" + soon, true,
                    now, 4102444801L, new JSONObject(), new java.math.BigInteger("9223372036854775808")}) {
                snapshot.put("nextResetCreditExpiresAt", badExpiry);
                store.saveSnapshot(snapshot, store.generation());
                WidgetState invalid = new QuotaStore(app).state();
                require(invalid.nextResetCreditExpiresAt == null && Double.valueOf(75).equals(invalid.weeklyRemaining),
                        "Malformed optional expiry does not invalidate quota or become valid after serialization");
            }
            snapshot.put("nextResetCreditExpiresAt", soon).put("availableResetCount", 0);
            store.saveSnapshot(snapshot, store.generation());
            require(new QuotaStore(app).state().nextResetCreditExpiresAt == null, "Stored zero suppresses expiry");
            snapshot.remove("availableResetCount");
            store.saveSnapshot(snapshot, store.generation());
            require(new QuotaStore(app).state().nextResetCreditExpiresAt == null, "Unknown stored count suppresses expiry");
            snapshot.put("availableResetCount", 2).remove("nextResetCreditExpiresAt");
            store.saveSnapshot(snapshot, store.generation());
            require(new QuotaStore(app).state().nextResetCreditExpiresAt == null,
                    "Old schemaVersion 1 snapshots remain readable without expiry");
            snapshot.put("updatedAt", now - 3600).put("nextResetCreditExpiresAt", now - 60);
            store.saveSnapshot(snapshot, store.generation());
            WidgetState elapsed = new QuotaStore(app).state();
            require(elapsed.nextResetCreditExpiresAt == null && elapsed.stale,
                    "An elapsed cached expiry is hidden and stale without initiating a refresh");
        } finally { store.clearSnapshot(); }
        require(QuotaStore.demoState().nextResetCreditExpiresAt > now, "Demo has an explicit future expiry");
    }

    private static void resetExpiryReadContract(Context app, JSONObject week, JSONObject five, long now) throws Exception {
        TokenVault vault = new TokenVault(app);
        JSONObject usage = payload(week, five).put("rate_limit_reset_credits", new JSONObject().put("available_count", 2));
        JSONObject detail = details(2,
                credit("synthetic-never-save-this-id", "available", "codex_rate_limits", Instant.ofEpochSecond(now + 3600).toString()),
                credit("synthetic-unlimited", "available", "codex_rate_limits", JSONObject.NULL));
        synchronized (TokenVault.LOCK) {
            vault.write(new TokenVault.Credentials("synthetic-read-token", "synthetic-refresh", "test-account", "Local test", now + 3600));
        }
        try {
            int[] requests = {0};
            long[] mainDeadline = {0};
            JSONObject snapshot = AccountClient.readQuota(app, () -> true, (endpoint, deadline) -> {
                requests[0]++;
                if (endpoint == AccountClient.QuotaEndpoint.USAGE) { mainDeadline[0] = deadline; return usage; }
                require(deadline <= mainDeadline[0] - 1000 && deadline <= SystemClock.elapsedRealtime() + 4000,
                        "Optional details have a short budget within the original operation");
                return detail;
            });
            require(requests[0] == 2 && snapshot.getLong("nextResetCreditExpiresAt") == now + 3600,
                    "Positive usage count reads and maps details in the same explicit operation");
            require(!snapshot.toString().contains("synthetic-never-save-this-id") && !snapshot.has("credits"),
                    "Snapshot keeps only the expiry, never raw detail objects or identifiers");
            for (Object count : new Object[]{0, JSONObject.NULL, "2", -1}) {
                JSONObject noDetails = payload(week, five).put("rate_limit_reset_credits", new JSONObject().put("available_count", count));
                requests[0] = 0;
                JSONObject result = AccountClient.readQuota(app, () -> true, (endpoint, deadline) -> {
                    requests[0]++;
                    require(endpoint == AccountClient.QuotaEndpoint.USAGE, "Zero or unknown count never requests details");
                    return noDetails;
                });
                require(requests[0] == 1 && result.isNull("nextResetCreditExpiresAt"),
                        "Zero or unknown counts clear earlier expiry without extra network work");
            }
            for (String code : new String[]{"network_timeout", "network_unavailable", "unauthorized", "access_unavailable",
                    "rate_limited", "invalid_response", "service_unavailable"}) {
                requests[0] = 0;
                JSONObject result = AccountClient.readQuota(app, () -> true, (endpoint, deadline) -> {
                    requests[0]++;
                    if (endpoint == AccountClient.QuotaEndpoint.USAGE) return usage;
                    throw new AccountClient.AccountException(code);
                });
                require(requests[0] == 2 && result.isNull("nextResetCreditExpiresAt")
                        && result.getJSONObject("weekly").getDouble("remainingPercent") == 75
                        && result.getLong("availableResetCount") == 2 && !result.getBoolean("stale"),
                        "Auxiliary failure preserves authoritative successful quota without retry or reauthentication");
            }
            JSONObject incomplete = details(2, detail.getJSONArray("credits").getJSONObject(0));
            JSONObject result = AccountClient.readQuota(app, () -> true, (endpoint, deadline) ->
                    endpoint == AccountClient.QuotaEndpoint.USAGE ? usage : incomplete);
            require(result.getLong("availableResetCount") == 2 && result.isNull("nextResetCreditExpiresAt"),
                    "Truncated details do not replace the main count or guess an expiry");
            boolean[] allowed = {true};
            try {
                AccountClient.readQuota(app, () -> allowed[0], (endpoint, deadline) -> {
                    if (endpoint == AccountClient.QuotaEndpoint.USAGE) return usage;
                    allowed[0] = false;
                    throw new AccountClient.AccountException("network_unavailable");
                });
                throw new AssertionError("Cancellation must propagate through an auxiliary failure");
            } catch (AccountClient.AccountException cancelled) {
                require("read_cancelled".equals(cancelled.getCode()), "Allowed predicate cancellation is not swallowed");
            }
            try {
                AccountClient.readQuota(app, () -> true, (endpoint, deadline) -> {
                    if (endpoint == AccountClient.QuotaEndpoint.USAGE) return usage;
                    AccountClient.cancelRead();
                    return detail;
                });
                throw new AssertionError("Explicit cancellation must propagate through an auxiliary success");
            } catch (AccountClient.AccountException cancelled) {
                require("read_cancelled".equals(cancelled.getCode()), "Explicit read cancellation is not swallowed");
            }
            try {
                AccountClient.readQuota(app, () -> true, (endpoint, deadline) -> {
                    if (endpoint == AccountClient.QuotaEndpoint.USAGE) return usage;
                    AccountClient.logout(app);
                    throw new AccountClient.AccountException("network_unavailable");
                });
                throw new AssertionError("Logout must propagate through an auxiliary failure");
            } catch (AccountClient.AccountException cancelled) {
                require("read_cancelled".equals(cancelled.getCode()) || "account_changed".equals(cancelled.getCode()),
                        "Logout during details cannot return a successful snapshot");
            }
        } finally { AccountClient.cancelRead(); AccountClient.logout(app); }
        require(!AccountClient.isSignedIn(app), "Quota-read test credentials are removed");
    }

    private static java.util.Map<String,String> parse(String request) throws Exception {
        return AccountClient.callbackParameters(new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)),
                1455,SystemClock.elapsedRealtime()+3000);
    }
}
