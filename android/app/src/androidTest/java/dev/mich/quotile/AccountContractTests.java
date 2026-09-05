package dev.mich.quotile;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private static java.util.Map<String,String> parse(String request) throws Exception {
        return AccountClient.callbackParameters(new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)),
                1455,SystemClock.elapsedRealtime()+3000);
    }
}
