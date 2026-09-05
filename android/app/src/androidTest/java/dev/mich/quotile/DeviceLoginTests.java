package dev.mich.quotile;

import android.app.job.JobScheduler;
import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Device-login contracts exercised with synthetic responses; never opens a real connection. */
final class DeviceLoginTests {
    private static final String USER_CODE = "ABCD-EFGH";
    private static final String DEVICE_ID = "synthetic-device-id";
    private static final String ACCESS = "synthetic-device-access-for-offline-test-only";
    private static final String VERIFIER = "synthetic-verifier-for-offline-contract-only-0123456789";
    private static final String CLIENT = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String VERIFY_URL = "https://auth.openai.com/codex/device";
    interface Checked { void run() throws Exception; }
    interface BeforeTokenReturns { void run(AccountClient.DeviceLoginSession session) throws Exception; }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    private static String rejects(Checked action, String label) throws Exception {
        try { action.run(); }
        catch (AccountClient.AccountException expected) { return expected.getCode(); }
        throw new AssertionError(label);
    }
    private static JSONObject code(String key, Object interval) throws Exception {
        JSONObject result = new JSONObject().put("device_auth_id", DEVICE_ID).put(key, USER_CODE);
        if (interval != null) result.put("interval", interval);
        // A response cannot redirect account entry to an arbitrary website.
        result.put("verification_url", "https://invalid.example/never-open-this");
        return result;
    }
    private static JSONObject authorized() throws Exception {
        String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)),
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        return new JSONObject().put("authorization_code", "synthetic-authorization-code")
                .put("code_challenge", challenge).put("code_verifier", VERIFIER);
    }
    private static JSONObject tokens() throws Exception {
        return new JSONObject().put("access_token", ACCESS).put("refresh_token", "synthetic-device-refresh")
                .put("token_type", "Bearer").put("expires_in", 3600);
    }
    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            require(pair.length == 2, "Device exchange uses form fields");
            String previous = result.put(URLDecoder.decode(pair[0], "UTF-8"),
                    URLDecoder.decode(pair[1], "UTF-8"));
            require(previous == null, "Device exchange has no duplicate fields");
        }
        return result;
    }

    /** Restrict fixtures to the three auth endpoints; no quota endpoint exists in this transport. */
    private static final class Script implements AccountClient.DeviceLoginTransport {
        JSONObject code;
        JSONObject authorization;
        JSONObject token;
        int codeStatus = 200;
        int tokenStatus = 200;
        int[] pollStatuses = {200};
        int requests, polls, exchanges;
        final List<Long> pollTimes = new ArrayList<>();
        BeforeTokenReturns beforeTokenReturns;
        CountDownLatch firstPoll;
        Script() throws Exception { code = code("user_code", "1"); authorization = authorized(); token = tokens(); }

        @Override public AccountClient.DeviceResponse post(AccountClient.DeviceEndpoint endpoint,
                String body, long deadline, AccountClient.DeviceLoginSession session)
                throws AccountClient.AccountException {
            try {
                requests++;
                require(deadline > SystemClock.elapsedRealtime(), "Each device request has a live deadline");
                switch (endpoint) {
                    case USER_CODE:
                        require(CLIENT.equals(new JSONObject(body).getString("client_id")),
                                "Code request identifies the public native OAuth client");
                        return new AccountClient.DeviceResponse(codeStatus, code);
                    case TOKEN_POLL:
                        JSONObject poll = new JSONObject(body);
                        require(DEVICE_ID.equals(poll.getString("device_auth_id")), "Poll is bound to this device request");
                        require(USER_CODE.equals(poll.getString("user_code")), "Poll sends the displayed one-time code");
                        pollTimes.add(SystemClock.elapsedRealtime());
                        int status = pollStatuses[Math.min(polls++, pollStatuses.length - 1)];
                        if (firstPoll != null) firstPoll.countDown();
                        return new AccountClient.DeviceResponse(status, status == 200 ? authorization : new JSONObject());
                    case OAUTH_TOKEN:
                        exchanges++;
                        Map<String, String> exchange = form(body);
                        require("authorization_code".equals(exchange.get("grant_type")), "Device authorization exchanges a code");
                        require(CLIENT.equals(exchange.get("client_id")), "Exchange uses the same public client");
                        require("https://auth.openai.com/deviceauth/callback".equals(exchange.get("redirect_uri")),
                                "Device exchange uses the official callback without localhost");
                        require("synthetic-authorization-code".equals(exchange.get("code")), "Exchange is bound to poll result");
                        require(VERIFIER.equals(exchange.get("code_verifier")), "Exchange retains the issued PKCE verifier");
                        if (beforeTokenReturns != null) beforeTokenReturns.run(session);
                        return new AccountClient.DeviceResponse(tokenStatus, token);
                    default: throw new AssertionError("Unexpected device transport endpoint");
                }
            } catch (AccountClient.AccountException expected) { throw expected; }
              catch (Exception fixtureFailure) { throw new AssertionError("Invalid synthetic device fixture", fixtureFailure); }
        }
    }

    static void run(Context app) throws Exception {
        AccountClient.logout(app);
        try {
            userCodeAliasesStayLocalUntilRequested(app);
            try (AccountClient.LoginSession browser = AccountClient.beginLogin(app)) {
                try (AccountClient.DeviceLoginSession device = AccountClient.beginDeviceLogin(app, new Script(), 5000)) {
                    require(browser.getAuthorizationUrl().isEmpty(), "Switching to device login closes the browser session");
                }
            }
            pendingResponsesRespectIntervalAndSuccessfulLoginDoesNotReadQuota(app);
            malformedResponsesCannotAuthenticate(app);
            terminalFailuresStop(app);
            cancellationStopsPendingPoll(app);
            pendingLoginExpires(app);
            lateExchangeCannotWrite(app);
        } finally { AccountClient.logout(app); }
        require(!AccountClient.isSignedIn(app), "Device test credentials were removed");
    }

    private static void userCodeAliasesStayLocalUntilRequested(Context app) throws Exception {
        for (String field : new String[]{"user_code", "usercode"}) {
            Script script = new Script();
            // The omitted interval remains compatible with responses lacking the optional field.
            script.code = code(field, null);
            try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 5000)) {
                require(script.requests == 0, "Preparing device login performs no network request");
                session.requestCode();
                require(USER_CODE.equals(session.getUserCode()), "Both server code field spellings are supported");
                require("waiting".equals(session.getStage()), "The activity can distinguish waiting for approval");
                require(VERIFY_URL.equals(session.getVerificationUrl()), "Account entry URL stays on the official origin");
                require(script.requests == 1 && script.polls == 0, "Requesting a code does not start an idle polling worker");
                require(!AccountClient.isSignedIn(app), "A displayed code is not an authenticated account");
            }
        }
    }

    private static void pendingResponsesRespectIntervalAndSuccessfulLoginDoesNotReadQuota(Context app) throws Exception {
        Script script = new Script();
        script.pollStatuses = new int[]{403, 404, 200};
        long oldReadTime = new QuotaStore(app).state().updatedAt;
        try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 10000)) {
            session.requestCode();
            session.awaitCompletion();
            require("completed".equals(session.getStage()), "Successful authentication exposes a completed stage");
            require(script.polls == 3 && script.exchanges == 1, "403 and 404 remain pending before a single successful exchange");
            for (int i = 1; i < script.pollTimes.size(); i++)
                require(script.pollTimes.get(i) - script.pollTimes.get(i - 1) >= 990,
                        "Pending authorization must honor the server interval in seconds");
            synchronized (TokenVault.LOCK) {
                TokenVault.Credentials stored = new TokenVault(app).read();
                require(stored != null && ACCESS.equals(stored.accessToken), "Only successful exchange persists synthetic credentials");
            }
            require(new QuotaStore(app).state().updatedAt == oldReadTime && !QuotaSync.isRunning(),
                    "Login completion never reads quota or changes its read timestamp");
            require(app.getSystemService(JobScheduler.class).getAllPendingJobs().isEmpty(),
                    "Device login never creates a background schedule");
        } finally { AccountClient.logout(app); }
    }

    private static void malformedResponsesCannotAuthenticate(Context app) throws Exception {
        for (Object interval : new Object[]{"-1", "1.5", true, JSONObject.NULL}) {
            Script script = new Script();
            script.code.put("interval", interval);
            try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 5000)) {
                require("invalid_response".equals(rejects(session::requestCode, "Invalid intervals are rejected")),
                        "Malformed intervals cannot start a rapid or unbounded polling loop");
                require(script.polls == 0, "Invalid code responses never begin polling");
            }
        }
        Script badProof = new Script();
        badProof.authorization.put("code_challenge", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, badProof, 5000)) {
            session.requestCode();
            require("invalid_response".equals(rejects(session::awaitCompletion, "Mismatched PKCE proof must fail")),
                    "The challenge must match the issued verifier");
            require(badProof.exchanges == 0 && !AccountClient.isSignedIn(app),
                    "An invalid proof never reaches token exchange or storage");
        }
        Script missingToken = new Script();
        missingToken.token.remove("access_token");
        try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, missingToken, 5000)) {
            session.requestCode();
            require("invalid_response".equals(rejects(session::awaitCompletion, "Missing access token must fail")),
                    "An HTTP success alone cannot authenticate an account");
            require(missingToken.exchanges == 1 && !AccountClient.isSignedIn(app),
                    "Malformed token response never writes credentials");
        }
    }

    private static void terminalFailuresStop(Context app) throws Exception {
        for (int status : new int[]{401, 429, 500, 302}) {
            Script script = new Script();
            script.pollStatuses = new int[]{status};
            try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 5000)) {
                session.requestCode();
                rejects(session::awaitCompletion, "Non-pending poll failures must terminate");
                require(script.polls == 1 && script.exchanges == 0, "A terminal response must not trigger retries or exchange");
                require(!AccountClient.isSignedIn(app), "Failed device authorization cannot sign in");
            }
        }
        Script disabled = new Script();
        disabled.codeStatus = 404;
        try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, disabled, 5000)) {
            rejects(session::requestCode, "A disabled device-code endpoint is a terminal startup failure");
            require(disabled.requests == 1 && disabled.polls == 0, "Unavailable device-code startup never polls");
        }
    }

    private static void cancellationStopsPendingPoll(Context app) throws Exception {
        Script script = new Script();
        script.pollStatuses = new int[]{403};
        script.firstPoll = new CountDownLatch(1);
        AtomicReference<Throwable> ended = new AtomicReference<>();
        AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 10000);
        Thread waiter = new Thread(() -> {
            try { session.awaitCompletion(); ended.set(new AssertionError("Cancelled login completed")); }
            catch (Throwable result) { ended.set(result); }
        }, "quotile-device-contract-cancel");
        try {
            session.requestCode();
            waiter.start();
            require(script.firstPoll.await(3, TimeUnit.SECONDS), "Synthetic pending response was reached");
            session.close();
            waiter.join(2000);
            require(!waiter.isAlive(), "Cancellation wakes authorization polling promptly");
            require(ended.get() instanceof AccountClient.AccountException, "Cancelled waiter reports a bounded login error");
            require(script.polls == 1 && script.exchanges == 0 && !AccountClient.isSignedIn(app),
                    "Cancelled authorization cannot poll again or save credentials");
        } finally { session.close(); waiter.interrupt(); waiter.join(2000); }
    }

    private static void pendingLoginExpires(Context app) throws Exception {
        Script script = new Script();
        script.pollStatuses = new int[]{404};
        try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, 250)) {
            session.requestCode();
            rejects(session::awaitCompletion, "Pending device login must expire");
            require(script.polls <= 1 && script.exchanges == 0 && !AccountClient.isSignedIn(app),
                    "The total lifetime prevents a second pending poll and credential persistence");
        }
    }

    private static void lateExchangeCannotWrite(Context app) throws Exception {
        for (String stop : new String[]{"close", "logout", "replacement", "browser_replacement", "expiry"}) {
            Script script = new Script();
            script.beforeTokenReturns = session -> {
                switch (stop) {
                    case "close": session.close(); break;
                    case "logout": AccountClient.logout(app); break;
                    case "replacement":
                        try (AccountClient.DeviceLoginSession newer = AccountClient.beginDeviceLogin(app, new Script(), 5000)) {
                            require(!AccountClient.isSignedIn(app), "Starting a replacement session is local only");
                        }
                        break;
                    case "browser_replacement":
                        try (AccountClient.LoginSession browser = AccountClient.beginLogin(app)) {
                            require(!browser.getAuthorizationUrl().isEmpty(), "Replacement browser login prepares locally");
                        }
                        break;
                    case "expiry": Thread.sleep(1600); break;
                    default: throw new AssertionError("Unknown invalidation fixture");
                }
            };
            long lifetime = "expiry".equals(stop) ? 1500 : 5000;
            try (AccountClient.DeviceLoginSession session = AccountClient.beginDeviceLogin(app, script, lifetime)) {
                session.requestCode();
                rejects(session::awaitCompletion, "An invalidated exchange must not complete");
                require(script.exchanges == 1, "Late-result fixture reached the token exchange");
                require(!AccountClient.isSignedIn(app), "A late token response after " + stop + " must not be written");
            } finally { AccountClient.logout(app); }
        }
    }
}
