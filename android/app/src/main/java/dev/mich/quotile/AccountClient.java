package dev.mich.quotile;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/**
 * Phone-local OAuth and caller-controlled quota reads. This client owns no background schedule,
 * alarms, polling, or model calls. The caller controls whether a pending read remains allowed.
 * Protocol provenance (OpenAI Apache-2.0 source, rust-v0.153.4):
 * https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/login/src/server.rs
 * https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/login/src/auth/manager.rs
 * https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/login/src/token_data.rs
 * https://github.com/openai/codex/blob/rust-v0.153.4/codex-rs/backend-client/src/client/rate_limit_resets.rs
 * This is an independent Android adaptation, not an official public Android SDK.
 * Login and endpoint availability require device testing and may change upstream.
 */
public final class AccountClient {
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"; // Public native OAuth client; no secret.
    private static final String AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final String QUOTA_URL = "https://chatgpt.com/backend-api/wham/usage";
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";
    private static final String PROFILE_CLAIM = "https://api.openai.com/profile";
    private static final Object READ_LOCK = new Object();
    private static LoginSession activeLogin;
    private static ReadSession activeRead;
    private AccountClient() {}

    public static final class AccountException extends Exception {
        private final String code;
        public AccountException(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }

    /** Local state only: neither method attempts token refresh or validates a remote account. */
    public static boolean isSignedIn(Context context) {
        synchronized (TokenVault.LOCK) {
            try { return new TokenVault(context).read() != null; }
            catch (Exception unavailable) { return false; }
        }
    }
    public static String accountLabel(Context context) {
        synchronized (TokenVault.LOCK) {
            try {
                TokenVault.Credentials credentials = new TokenVault(context).read();
                return credentials == null ? "" : credentials.label;
            } catch (Exception unavailable) { return ""; }
        }
    }

    /** Local logout only. It invalidates pending writes without sending a revocation request. */
    public static void logout(Context context) throws AccountException {
        synchronized (TokenVault.LOCK) {
            TokenVault.generation++;
            if (activeLogin != null) activeLogin.close();
            if (activeRead != null) activeRead.close();
            try { new TokenVault(context).clear(); }
            catch (Exception unavailable) { throw new AccountException("storage_unavailable"); }
        }
    }

    /** Binds a short-lived loopback callback. The caller explicitly opens the returned URL. */
    public static LoginSession beginLogin(Context context) throws AccountException {
        synchronized (TokenVault.LOCK) {
            if (activeLogin != null) activeLogin.close();
            if (activeRead != null) activeRead.close();
            long generation = ++TokenVault.generation;
            try {
                activeLogin = new LoginSession(context.getApplicationContext(), generation);
                return activeLogin;
            } catch (Exception unavailable) { throw new AccountException("login_listener_unavailable"); }
        }
    }

    /** Cancels only the current read, without logging out or changing the vault. */
    public static void cancelRead() {
        synchronized (TokenVault.LOCK) {
            if (activeRead != null) activeRead.close();
        }
    }

    /**
     * Lives only for one caller-controlled operation. There is no shared scheduler or idle thread.
     * Disconnect is best effort: Android's OS DNS/transport work cannot always be interrupted.
     * Cancellation still prevents later stages and persistence when a blocked API call returns.
     */
    private static final class DeadlineWatch implements AutoCloseable {
        private final Thread thread;
        private volatile boolean closed;
        DeadlineWatch(long deadline, Runnable expire) {
            thread = new Thread(() -> {
                try {
                    while (!closed) {
                        long remaining = deadline - SystemClock.elapsedRealtime();
                        if (remaining <= 0) {
                            if (!closed) expire.run();
                            return;
                        }
                        Thread.sleep(remaining);
                    }
                } catch (InterruptedException cancelled) { /* Operation completed or cancelled. */ }
            }, "quotile-operation-deadline");
            thread.setDaemon(true);
            thread.start();
        }
        @Override public void close() { closed = true; thread.interrupt(); }
    }

    private static final class ReadSession implements AutoCloseable {
        final long generation, deadline;
        final java.util.function.BooleanSupplier allowed;
        volatile boolean closed, timedOut;
        HttpsURLConnection connection;
        DeadlineWatch watch;
        ReadSession(long generation, long deadline, java.util.function.BooleanSupplier allowed) {
            this.generation = generation; this.deadline = deadline;
            this.allowed = allowed;
        }
        void start() {
            watch = new DeadlineWatch(deadline, () -> {
                synchronized (TokenVault.LOCK) { timedOut = true; close(); }
            });
        }
        void check() throws AccountException {
            synchronized (TokenVault.LOCK) {
                if (timedOut || SystemClock.elapsedRealtime() >= deadline) throw new AccountException("network_timeout");
                if (closed || !allowed.getAsBoolean()) throw new AccountException("read_cancelled");
                checkGeneration(generation);
            }
        }
        void register(HttpsURLConnection next) throws AccountException {
            synchronized (TokenVault.LOCK) { check(); connection = next; }
        }
        void release(HttpsURLConnection previous) {
            synchronized (TokenVault.LOCK) { if (connection == previous) connection = null; }
        }
        @Override public void close() {
            synchronized (TokenVault.LOCK) {
                closed = true;
                if (activeRead == this) activeRead = null;
                if (watch != null) watch.close();
                HttpsURLConnection current = connection;
                connection = null;
                if (current != null) current.disconnect();
            }
        }
    }

    public static final class LoginSession implements AutoCloseable {
        private final Context context;
        private final long generation, deadline;
        private final ServerSocket server;
        private final String redirect;
        private byte[] state, verifier;
        private String authorizationUrl;
        private volatile boolean closed, completed, timedOut;
        private volatile Socket currentSocket;
        private volatile HttpsURLConnection currentConnection;
        private boolean awaiting;
        private DeadlineWatch watch;

        private LoginSession(Context context, long generation) throws Exception {
            this.context = context; this.generation = generation;
            this.deadline = SystemClock.elapsedRealtime() + 180000;
            ServerSocket bound = null;
            // Both redirect ports are explicitly allowlisted in upstream server.rs.
            for (int port : new int[]{1455, 1457}) {
                ServerSocket candidate = new ServerSocket();
                try {
                    candidate.setReuseAddress(false);
                    candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
                    candidate.setSoTimeout(1000); bound = candidate; break;
                } catch (java.io.IOException busy) { try { candidate.close(); } catch (Exception ignored) {} }
            }
            if (bound == null) throw new java.io.IOException("callback_port_unavailable");
            this.server = bound;
            this.redirect = "http://localhost:" + server.getLocalPort() + "/auth/callback";
            try {
                state = randomEncoded(); verifier = randomEncoded();
                String challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier),
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                values.put("response_type", "code"); values.put("client_id", CLIENT_ID);
                values.put("redirect_uri", redirect);
                // Deliberately omit upstream connector scopes: this application never calls connectors.
                // The reduced scope set and Android browser flow still need an actual login test.
                values.put("scope", "openid profile email offline_access");
                values.put("code_challenge", challenge); values.put("code_challenge_method", "S256");
                values.put("id_token_add_organizations", "true"); values.put("codex_cli_simplified_flow", "true");
                values.put("state", new String(state, StandardCharsets.US_ASCII));
                values.put("originator", "quotile_android");
                authorizationUrl = AUTHORIZE_URL + "?" + form(values);
            } catch (Exception invalid) { server.close(); throw invalid; }
        }

        public String getAuthorizationUrl() { return closed ? "" : authorizationUrl; }

        /** Run off the main thread only after the user taps login. Stops within the login window. */
        public void awaitCompletion() throws AccountException {
            synchronized (this) {
                if (awaiting || closed) throw new AccountException("login_cancelled");
                awaiting = true;
            }
            synchronized (TokenVault.LOCK) {
                if (closed) throw new AccountException("login_cancelled");
                watch = new DeadlineWatch(deadline, () -> {
                    synchronized (TokenVault.LOCK) { timedOut = true; close(); }
                });
            }
            try {
                while (!closed && SystemClock.elapsedRealtime() < deadline) {
                    Socket socket;
                    try { socket = server.accept(); }
                    catch (SocketTimeoutException timeout) { continue; }
                    currentSocket = socket;
                    try (Socket connection = socket) {
                        if (!connection.getInetAddress().isLoopbackAddress()) continue;
                        connection.setSoTimeout((int) Math.min(3000, Math.max(1, deadline - SystemClock.elapsedRealtime())));
                        Map<String, String> parameters;
                        try { parameters = callbackParameters(connection.getInputStream(), server.getLocalPort(), deadline); }
                        catch (Exception invalid) { respond(connection, false); continue; }
                        byte[] expected = state;
                        String returned = parameters.get("state");
                        if (expected == null || returned == null || !MessageDigest.isEqual(expected, returned.getBytes(StandardCharsets.UTF_8))) {
                            respond(connection, false); continue;
                        }
                        if (parameters.containsKey("error")) {
                            respond(connection, false); throw new AccountException("login_rejected");
                        }
                        String code = parameters.get("code");
                        if (code == null || code.isEmpty() || code.length() > 8192) { respond(connection, false); continue; }
                        try {
                            byte[] verifierCopy;
                            synchronized (TokenVault.LOCK) {
                                checkGeneration(generation);
                                if (closed || verifier == null) throw new AccountException("login_cancelled");
                                verifierCopy = verifier.clone();
                            }
                            JSONObject response;
                            try {
                                LinkedHashMap<String, String> body = new LinkedHashMap<>();
                                body.put("grant_type", "authorization_code"); body.put("code", code);
                                body.put("redirect_uri", redirect); body.put("client_id", CLIENT_ID);
                                body.put("code_verifier", new String(verifierCopy, StandardCharsets.US_ASCII));
                                response = request(TOKEN_URL, "POST", form(body), "application/x-www-form-urlencoded",
                                        null, Math.min(deadline, SystemClock.elapsedRealtime() + 25000), this, null, true);
                            } finally { Arrays.fill(verifierCopy, (byte) 0); }
                            TokenVault.Credentials credentials = credentials(response, null);
                            synchronized (TokenVault.LOCK) {
                                checkGeneration(generation);
                                if (closed) throw new AccountException("login_cancelled");
                                try { new TokenVault(context).write(credentials); }
                                catch (Exception failure) { throw new AccountException("storage_unavailable"); }
                                completed = true;
                            }
                            respond(connection, true);
                            return; // Login success never fetches usage.
                        } catch (AccountException failure) { respond(connection, false); throw failure; }
                    } finally { currentSocket = null; }
                }
                throw new AccountException(timedOut || !closed ? "login_timeout" : "login_cancelled");
            } catch (AccountException failure) {
                if (timedOut) throw new AccountException("login_timeout");
                throw failure;
            } catch (Exception failure) {
                throw new AccountException(timedOut ? "login_timeout" : closed ? "login_cancelled" : "login_failed");
            }
            finally { close(); }
        }

        @Override public void close() {
            synchronized (TokenVault.LOCK) {
                closed = true;
                if (watch != null) watch.close();
                if (!completed && generation == TokenVault.generation) TokenVault.generation++;
                if (activeLogin == this) activeLogin = null;
                if (state != null) { Arrays.fill(state, (byte) 0); state = null; }
                if (verifier != null) { Arrays.fill(verifier, (byte) 0); verifier = null; }
                // Java strings cannot be forcibly erased; discard transient URL references promptly.
                authorizationUrl = null;
                try { server.close(); } catch (Exception ignored) {}
                Socket socket = currentSocket;
                if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                HttpsURLConnection connection = currentConnection;
                if (connection != null) connection.disconnect();
            }
        }
    }

    /** A failed request throws without changing the caller's last snapshot. */
    public static JSONObject readQuota(Context context) throws AccountException {
        return readQuota(context, () -> true);
    }

    /**
     * The predicate closes the queued-worker/start race when the user cancels or disables refresh.
     * It must be fast, local, and free of locks which could wait on this client's vault lock.
     */
    public static JSONObject readQuota(Context context, java.util.function.BooleanSupplier allowed) throws AccountException {
        if (allowed == null) throw new IllegalArgumentException("allowed");
        if (!allowed.getAsBoolean()) throw new AccountException("read_cancelled");
        synchronized (READ_LOCK) {
            if (!allowed.getAsBoolean()) throw new AccountException("read_cancelled");
            long deadline = SystemClock.elapsedRealtime() + 25000;
            TokenVault vault = new TokenVault(context);
            TokenVault.Credentials credentials;
            ReadSession session;
            synchronized (TokenVault.LOCK) {
                if (!allowed.getAsBoolean()) throw new AccountException("read_cancelled");
                try { credentials = vault.read(); }
                catch (Exception unavailable) { throw new AccountException("storage_unavailable"); }
                if (!allowed.getAsBoolean()) throw new AccountException("read_cancelled");
                if (credentials == null) throw new AccountException("login_required");
                session = new ReadSession(TokenVault.generation, deadline, allowed);
                activeRead = session;
                session.start();
            }
            try (ReadSession operation = session) {
                operation.check();
                boolean refreshed = false;
                if (credentials.expiresAt > 0 && credentials.expiresAt <= System.currentTimeMillis() / 1000 + 60) {
                    credentials = refresh(vault, credentials, operation); refreshed = true;
                }
                JSONObject payload;
                try { payload = request(QUOTA_URL, "GET", null, null, credentials, deadline, null, operation, false); }
                catch (AccountException rejected) {
                    operation.check();
                    if (!rejected.getCode().equals("unauthorized") || refreshed) {
                        if (rejected.getCode().equals("unauthorized")) throw new AccountException("login_required");
                        throw rejected;
                    }
                    credentials = refresh(vault, credentials, operation);
                    try { payload = request(QUOTA_URL, "GET", null, null, credentials, deadline, null, operation, false); }
                    catch (AccountException failure) {
                        operation.check();
                        if (failure.getCode().equals("unauthorized")) throw new AccountException("login_required");
                        throw failure;
                    }
                }
                operation.check();
                try { return RateLimitParser.parse(payload, System.currentTimeMillis() / 1000); }
                catch (AccountException unavailable) { throw unavailable; }
                catch (Exception invalid) { throw new AccountException("invalid_response"); }
            }
        }
    }

    private static TokenVault.Credentials refresh(TokenVault vault, TokenVault.Credentials old, ReadSession operation)
            throws AccountException {
        operation.check();
        if (old.refreshToken.isEmpty()) throw new AccountException("login_required");
        JSONObject response;
        try {
            JSONObject body = new JSONObject().put("client_id", CLIENT_ID).put("grant_type", "refresh_token")
                    .put("refresh_token", old.refreshToken);
            response = request(TOKEN_URL, "POST", body.toString(), "application/json", null, operation.deadline, null, operation, true);
        } catch (AccountException failure) { throw failure; }
          catch (Exception invalid) { throw new AccountException("invalid_response"); }
        TokenVault.Credentials fresh = credentials(response, old);
        if (!old.accountId.isEmpty() && !fresh.accountId.equals(old.accountId)) throw new AccountException("account_changed");
        synchronized (TokenVault.LOCK) {
            operation.check();
            try { vault.write(fresh); }
            catch (Exception failure) { throw new AccountException("storage_unavailable"); }
        }
        return fresh;
    }

    private static TokenVault.Credentials credentials(JSONObject response, TokenVault.Credentials old) throws AccountException {
        try {
            String access = token(response, "access_token", null);
            String refresh = token(response, "refresh_token", old == null ? "" : old.refreshToken);
            String id = token(response, "id_token", "");
            String type = response.optString("token_type", "Bearer");
            if (!type.equalsIgnoreCase("Bearer")) throw new IllegalArgumentException("token_type");
            JSONObject accessClaims = jwtPayload(access), idClaims = jwtPayload(id);
            // Claims arrive in authenticated TLS token responses. They supply display/routing hints,
            // never local authorization: the quota server validates the bearer token itself.
            JSONObject auth = idClaims.optJSONObject(AUTH_CLAIM);
            if (auth == null) auth = accessClaims.optJSONObject(AUTH_CLAIM);
            if (auth != null && auth.optBoolean("chatgpt_account_is_fedramp", false))
                throw new AccountException("account_not_supported");
            String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
            if (account.isEmpty() && old != null) account = old.accountId;
            if (!account.isEmpty() && !account.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException("account_id");
            String label = idClaims.optString("email", "");
            JSONObject profile = idClaims.optJSONObject(PROFILE_CLAIM);
            if (label.isEmpty() && profile != null) label = profile.optString("email", "");
            if (label.isEmpty() && old != null) label = old.label;
            label = label.replaceAll("[\\p{Cntrl}\\p{Cf}]", "");
            if (label.length() > 160) label = label.substring(0, 160);
            if (label.isEmpty()) label = "已登录 ChatGPT";
            long expires = 0;
            Object lifetime = response.opt("expires_in");
            if (lifetime instanceof Number) {
                long seconds = ((Number) lifetime).longValue();
                if (seconds > 0 && seconds <= 31536000) expires = System.currentTimeMillis() / 1000 + seconds;
            }
            if (expires == 0) {
                Object exp = accessClaims.opt("exp");
                if (exp instanceof Number && ((Number) exp).longValue() > 0) expires = ((Number) exp).longValue();
            }
            return new TokenVault.Credentials(access, refresh, account, label, expires);
        } catch (AccountException failure) { throw failure; }
          catch (Exception invalid) { throw new AccountException("invalid_response"); }
    }

    private static String token(JSONObject response, String key, String fallback) throws Exception {
        if (!response.has(key) || response.isNull(key)) {
            if (fallback != null) return fallback;
            throw new IllegalArgumentException("missing_token");
        }
        Object raw = response.get(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException("token_type");
        String value = (String) raw;
        if (value.isEmpty() || value.length() > 32768 || !value.matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException("token_format");
        return value;
    }

    private static JSONObject jwtPayload(String token) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3 || segments[1].length() > 24576) return new JSONObject();
            return new JSONObject(new String(Base64.decode(segments[1], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
        } catch (Exception opaqueToken) { return new JSONObject(); }
    }

    private static JSONObject request(String endpoint, String method, String body, String contentType,
            TokenVault.Credentials credentials, long deadline, LoginSession login, ReadSession operation, boolean auth) throws AccountException {
        HttpsURLConnection connection = null;
        DeadlineWatch requestWatch = null;
        try {
            if (!TOKEN_URL.equals(endpoint) && !QUOTA_URL.equals(endpoint)) throw new AccountException("invalid_endpoint");
            checkRequest(login, operation);
            int timeout = remaining(deadline);
            connection = (HttpsURLConnection) new URL(endpoint).openConnection();
            if (login != null) {
                synchronized (TokenVault.LOCK) {
                    checkRequest(login, operation);
                    login.currentConnection = connection;
                }
            }
            if (operation != null) operation.register(connection);
            // Login has a three-minute browser window but token exchange gets its own 25-second
            // disconnect watchdog. Read operations already own a watchdog spanning all requests.
            if (login != null) {
                HttpsURLConnection tokenConnection = connection;
                requestWatch = new DeadlineWatch(deadline, tokenConnection::disconnect);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method); connection.setUseCaches(false);
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Quotile/0.2.0 (Android; independent quota widget)");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (credentials != null) {
                connection.setRequestProperty("Authorization", "Bearer " + credentials.accessToken);
                if (!credentials.accountId.isEmpty()) connection.setRequestProperty("ChatGPT-Account-Id", credentials.accountId);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                    connection.setRequestProperty("Content-Type", contentType);
                    checkRequest(login, operation);
                    try (OutputStream output = connection.getOutputStream()) {
                        checkRequest(login, operation); remaining(deadline); output.write(bytes);
                    }
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
            connection.setReadTimeout(remaining(deadline));
            checkRequest(login, operation);
            int status = connection.getResponseCode();
            checkRequest(login, operation); remaining(deadline);
            if (status != 200) {
                if (status == 401) throw new AccountException(auth ? "login_required" : "unauthorized");
                if (auth && status == 400) throw new AccountException("login_required");
                if (status == 403) throw new AccountException("access_unavailable");
                if (status == 429) throw new AccountException("rate_limited");
                if (status >= 300 && status < 400) throw new AccountException("unexpected_redirect");
                throw new AccountException("service_unavailable");
            }
            String type = connection.getContentType();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                throw new AccountException("invalid_response");
            if (connection.getContentLengthLong() > 65536) throw new AccountException("invalid_response");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while (true) {
                    checkRequest(login, operation);
                    connection.setReadTimeout(remaining(deadline));
                    count = input.read(buffer);
                    if (count == -1) break;
                    if (output.size() + count > 65536) throw new AccountException("invalid_response");
                    output.write(buffer, 0, count);
                }
                remaining(deadline);
                checkRequest(login, operation);
                return new JSONObject(output.toString("UTF-8"));
            }
        } catch (AccountException failure) { throw failure; }
          catch (javax.net.ssl.SSLException tls) {
              checkRequest(login, operation); remaining(deadline);
              throw new AccountException("tls_error");
          }
          catch (SocketTimeoutException timeout) { checkRequest(login, operation); throw new AccountException("network_timeout"); }
          catch (java.io.IOException offline) {
              checkRequest(login, operation); remaining(deadline);
              throw new AccountException("network_unavailable");
          }
          catch (Exception invalid) { throw new AccountException("invalid_response"); }
        finally {
            if (requestWatch != null) requestWatch.close();
            if (login != null) {
                synchronized (TokenVault.LOCK) {
                    if (login.currentConnection == connection) login.currentConnection = null;
                }
            }
            if (operation != null) operation.release(connection);
            if (connection != null) connection.disconnect();
        }
    }

    private static void checkRequest(LoginSession login, ReadSession operation) throws AccountException {
        synchronized (TokenVault.LOCK) {
            if (operation != null) operation.check();
            if (login != null) {
                if (login.timedOut) throw new AccountException("login_timeout");
                if (login.closed) throw new AccountException("login_cancelled");
                checkGeneration(login.generation);
            }
        }
    }

    private static int remaining(long deadline) throws AccountException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new AccountException("network_timeout");
        return (int) Math.min(7000, remaining);
    }
    private static void checkGeneration(long expected) throws AccountException {
        if (expected != TokenVault.generation) throw new AccountException("account_changed");
    }
    private static byte[] randomEncoded() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        byte[] encoded = Base64.encode(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        Arrays.fill(bytes, (byte) 0); return encoded;
    }
    private static String form(Map<String, String> values) throws Exception {
        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (output.length() > 0) output.append('&');
            output.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return output.toString();
    }

    /** Strict bounded parser: reject duplicate parameters/Host headers, bodies and alternate paths. */
    static Map<String, String> callbackParameters(InputStream input, int port, long deadline) throws Exception {
        int[] size = new int[]{0};
        String request = line(input, size, deadline);
        String[] parts = request.split(" ", -1);
        if (parts.length != 3 || !parts[0].equals("GET") || !parts[2].equals("HTTP/1.1"))
            throw new IllegalArgumentException("request_line");
        URI target = new URI(parts[1]);
        if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawFragment() != null
                || !"/auth/callback".equals(target.getRawPath())) throw new IllegalArgumentException("callback_path");
        String host = null;
        for (int count = 0; count < 64; count++) {
            String header = line(input, size, deadline);
            if (header.isEmpty()) break;
            if (count == 63 || header.startsWith(" ") || header.startsWith("\t")) throw new IllegalArgumentException("headers");
            int colon = header.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("header");
            String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+")) throw new IllegalArgumentException("header_name");
            if (name.equals("host")) {
                if (host != null) throw new IllegalArgumentException("duplicate_host");
                host = value;
            }
            if (name.equals("transfer-encoding") || (name.equals("content-length") && !value.equals("0")))
                throw new IllegalArgumentException("request_body");
        }
        if (!("localhost:" + port).equalsIgnoreCase(host)) throw new IllegalArgumentException("callback_host");
        String query = target.getRawQuery();
        if (query == null || query.length() > 12288) throw new IllegalArgumentException("callback_query");
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 1) throw new IllegalArgumentException("callback_parameter");
            String key = URLDecoder.decode(pair.substring(0, equals), "UTF-8");
            String value = URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
            if (key.isEmpty() || result.containsKey(key) || key.matches(".*[\\p{Cntrl}].*")
                    || value.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("callback_parameter");
            result.put(key, value);
            if (result.size() > 12) throw new IllegalArgumentException("callback_parameter_count");
        }
        if (result.containsKey("error") && result.containsKey("code")) throw new IllegalArgumentException("ambiguous_callback");
        return result;
    }
    private static String line(InputStream input, int[] size, long deadline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean carriage = false;
        while (true) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException();
            int next = input.read();
            if (next < 0 || ++size[0] > 24576 || output.size() > 16384) throw new IllegalArgumentException("http_size");
            if (carriage) {
                if (next != '\n') throw new IllegalArgumentException("http_line");
                return output.toString("US-ASCII");
            }
            if (next == '\r') { carriage = true; continue; }
            if (next != '\t' && (next < 32 || next > 126)) throw new IllegalArgumentException("http_character");
            output.write(next);
        }
    }
    private static void respond(Socket socket, boolean success) {
        // Fixed content: never reflect callback parameters or include credentials in a browser page.
        String text = success ? "登录已完成。请返回「余量」，点击刷新读取额度。" : "登录未完成。请返回「余量」查看状态或重新登录。";
        byte[] body = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>余量</title><p>" + text + "</p></html>")
                .getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + (success ? "200 OK" : "400 Bad Request") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nPragma: no-cache\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'\r\n"
                + "X-Content-Type-Options: nosniff\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        try { OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush(); }
        catch (Exception disconnected) { /* No request, response or credential logging. */ }
    }
}
