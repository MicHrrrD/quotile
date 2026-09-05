package dev.mich.quotile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private configuration and last server snapshot. OpenAI credentials never enter this app. */
public final class QuotaStore {
    private static final Object LOCK = new Object();
    private static final String KEY_ALIAS = "quotile.pairing.v1";
    private final SharedPreferences prefs;

    public QuotaStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("quotile", Context.MODE_PRIVATE);
    }
    public String endpoint() { return prefs.getString("endpoint", ""); }
    public String theme() { return prefs.getString("theme", "system"); }
    public int intervalMinutes() { return prefs.getInt("interval", 30); }
    public boolean demo() { return prefs.getBoolean("demo", false); }
    public long generation() { return prefs.getLong("generation", 0); }
    public String token() {
        synchronized (LOCK) {
            String ciphertext = prefs.getString("token", "");
            if (ciphertext.isEmpty()) return "";
            try {
                byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception unavailable) { return ""; }
        }
    }
    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static String normalizeEndpoint(String input) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("请填写 HTTPS 服务地址");
        try {
            URI uri = new URI(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("使用 HTTPS 地址，地址中不要包含配对码、查询参数或账号");
            String path = uri.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/v1/quota";
            if (!path.equals("/v1/quota")) throw new IllegalArgumentException("服务地址路径应为 /v1/quota，或只填写域名");
            if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("端口无效");
            return new URI("https", null, uri.getHost(), uri.getPort(), path, null, null).toASCIIString();
        } catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("服务地址格式不正确"); }
    }
    public void configure(String endpoint, String token, String theme, int interval, boolean demo) throws Exception {
        synchronized (LOCK) {
            String cleanEndpoint = endpoint == null ? "" : endpoint.trim();
            String cleanToken = token == null ? "" : token.trim();
            if (!demo || !cleanEndpoint.isEmpty()) cleanEndpoint = normalizeEndpoint(cleanEndpoint);
            if (!demo && !cleanToken.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("请填写桥接服务生成的完整配对码");
            if (!cleanToken.isEmpty() && !cleanToken.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalArgumentException("配对码格式不正确");
            if (!theme.equals("light") && !theme.equals("dark") && !theme.equals("system")) theme = "system";
            if (interval != 15 && interval != 30 && interval != 60) interval = 30;
            boolean changed = !cleanEndpoint.equals(endpoint()) || !cleanToken.equals(token());
            SharedPreferences.Editor edit = prefs.edit().putString("endpoint", cleanEndpoint).putString("theme", theme)
                    .putInt("interval", interval).putBoolean("demo", demo).putLong("generation", generation() + 1);
            if (cleanToken.isEmpty()) edit.remove("token").remove("iv");
            else {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey());
                edit.putString("token", Base64.encodeToString(cipher.doFinal(cleanToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP));
                edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            }
            if (changed) edit.remove("snapshot").remove("lastError");
            if (!edit.commit()) throw new java.io.IOException("配置未能保存，请重试");
        }
    }
    public void clear() {
        synchronized (LOCK) {
            long next = generation() + 1;
            prefs.edit().clear().putLong("generation", next).commit();
        }
    }
    public WidgetState state() {
        if (demo()) return demoState();
        WidgetState state = new WidgetState();
        state.configured = !endpoint().isEmpty() && !token().isEmpty();
        try {
            String snapshot = prefs.getString("snapshot", "");
            if (!snapshot.isEmpty()) state = parse(new JSONObject(snapshot));
        } catch (Exception invalid) { state.error = "invalid_response"; }
        state.configured = !endpoint().isEmpty() && !token().isEmpty();
        String error = prefs.getString("lastError", "");
        if (!error.isEmpty()) { state.error = error; state.stale = true; }
        long now = System.currentTimeMillis() / 1000;
        if (state.updatedAt > 0 && now - state.updatedAt > intervalMinutes() * 120L) state.stale = true;
        if ((state.weeklyResetAt > 0 && now >= state.weeklyResetAt)
                || (state.fiveHourResetAt > 0 && now >= state.fiveHourResetAt)) state.stale = true;
        return state;
    }
    public void saveSnapshot(JSONObject json, long expectedGeneration) throws Exception {
        parse(json); // Validate the complete response before replacing a previously good snapshot.
        synchronized (LOCK) {
            if (generation() == expectedGeneration) prefs.edit().putString("snapshot", json.toString()).remove("lastError").commit();
        }
    }
    public void saveError(String error, long expectedGeneration) {
        synchronized (LOCK) {
            if (generation() == expectedGeneration) prefs.edit().putString("lastError", error).commit();
        }
    }
    private static WidgetState parse(JSONObject json) throws Exception {
        if (json.getInt("schemaVersion") != 1) throw new IllegalArgumentException("schemaVersion");
        WidgetState state = new WidgetState();
        state.configured = true;
        state.plan = json.isNull("plan") ? null : json.optString("plan", null);
        if (state.plan != null && state.plan.length() > 80) state.plan = null;
        state.updatedAt = json.getLong("updatedAt");
        if (state.updatedAt < 0 || state.updatedAt > System.currentTimeMillis()/1000 + 300) throw new IllegalArgumentException("updatedAt");
        state.stale = json.getBoolean("stale");
        state.error = json.isNull("error") ? null : json.optString("error", null);
        if (!json.has("weekly") || !json.has("fiveHour")) throw new IllegalArgumentException("missing windows");
        JSONObject weekly = json.isNull("weekly") ? null : json.getJSONObject("weekly");
        JSONObject fiveHour = json.isNull("fiveHour") ? null : json.getJSONObject("fiveHour");
        if (weekly != null) { state.weeklyRemaining = percent(weekly); state.weeklyResetAt = reset(weekly); }
        if (fiveHour != null) { state.fiveHourRemaining = percent(fiveHour); state.fiveHourResetAt = reset(fiveHour); }
        if ((weekly != null || fiveHour != null) && state.updatedAt == 0) throw new IllegalArgumentException("missing timestamp");
        return state;
    }
    private static double percent(JSONObject window) throws Exception {
        Object raw = window.get("remainingPercent");
        if (!(raw instanceof Number)) throw new IllegalArgumentException("percent type");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 100) throw new IllegalArgumentException("percent");
        return value;
    }
    private static long reset(JSONObject window) throws Exception {
        long value = window.getLong("resetsAt");
        if (value < 0 || value > 4102444800L) throw new IllegalArgumentException("reset timestamp");
        return value;
    }
    public static WidgetState demoState() {
        WidgetState state = new WidgetState();
        state.weeklyRemaining = 68.0; state.fiveHourRemaining = 84.0;
        long now = System.currentTimeMillis()/1000;
        state.weeklyResetAt = now + 3 * 86400 + 7200; state.fiveHourResetAt = now + 9000;
        state.updatedAt = now; state.demo = true; state.configured = true; state.plan = "Pro 5x";
        return state;
    }
}
