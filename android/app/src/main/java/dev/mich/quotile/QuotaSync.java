package dev.mich.quotile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

public final class QuotaSync {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static long lastAttemptElapsed = -60000;
    private static long lastGeneration = -1;
    private QuotaSync() {}

    public static void refreshAsync(Context context, Runnable callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try { refresh(app); }
            finally {
                new Handler(Looper.getMainLooper()).post(() -> {
                    WidgetUpdate.updateAll(app);
                    if (callback != null) callback.run();
                });
            }
        });
    }
    private static void refresh(Context context) {
        QuotaStore store = new QuotaStore(context);
        long generation = store.generation();
        String endpoint = store.endpoint(), token = store.token();
        if (store.demo() || endpoint.isEmpty() || token.isEmpty()) return;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        if (lastGeneration == generation && elapsed - lastAttemptElapsed < 10000) return;
        lastAttemptElapsed = elapsed; lastGeneration = generation;
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(QuotaStore.normalizeEndpoint(endpoint));
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false); // Never forward pairing credentials to a redirected host.
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status != 200) {
                store.saveError(status == 401 || status == 403 ? "pairing_rejected" : "service_unavailable", generation);
                return;
            }
            String type = connection.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
                throw new IllegalArgumentException("content type");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[2048]; int read;
                while ((read = input.read(chunk)) != -1) {
                    if (output.size() + read > 32768) throw new IllegalArgumentException("response too large");
                    output.write(chunk, 0, read);
                }
                store.saveSnapshot(new JSONObject(output.toString("UTF-8")), generation);
            }
        } catch (javax.net.ssl.SSLException tls) { store.saveError("tls_error", generation); }
          catch (java.io.IOException offline) { store.saveError("network_unavailable", generation); }
          catch (Exception invalid) { store.saveError("invalid_response", generation); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
