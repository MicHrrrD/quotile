package dev.mich.quotile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Local display preferences and the last quota snapshot. */
public final class QuotaStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final Context app;

    public QuotaStore(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences("quotile", Context.MODE_PRIVATE);
    }
    public String theme() { return prefs.getString("theme", "system"); }
    public boolean demo() { return prefs.getBoolean("demo", false); }
    public boolean automatic() { return prefs.getBoolean("automatic", false); }
    public int intervalMinutes() {
        int value = prefs.getInt("interval", 30);
        return value == 15 || value == 60 ? value : 30;
    }
    public long generation() { return prefs.getLong("generation", 0); }

    public void savePreferences(String theme, boolean demo) throws Exception {
        savePreferences(theme, demo, automatic(), intervalMinutes());
    }
    public void savePreferences(String theme, boolean demo, boolean automatic, int interval) throws Exception {
        synchronized (LOCK) {
            if (!"light".equals(theme) && !"dark".equals(theme) && !"system".equals(theme)) theme = "system";
            if (interval != 15 && interval != 30 && interval != 60) interval = 30;
            if (!prefs.edit().putString("theme", theme).putBoolean("demo", demo)
                    .putBoolean("automatic", automatic).putInt("interval", interval)
                    .putLong("generation", generation() + (demo != demo() ? 1 : 0)).commit())
                throw new java.io.IOException("Settings could not be saved");
        }
    }
    public void setAutomatic(boolean automatic, int interval) throws Exception {
        synchronized (LOCK) {
            if (interval != 15 && interval != 30 && interval != 60) interval = 30;
            if (!prefs.edit().putBoolean("automatic", automatic).putInt("interval", interval).commit())
                throw new java.io.IOException("Automatic refresh preference could not be saved");
        }
    }
    public void clearSnapshot() {
        synchronized (LOCK) {
            prefs.edit().remove("snapshot").remove("lastError")
                    .putLong("generation", generation() + 1).commit();
        }
    }
    /** Upgrade cleanup only: never starts a scheduled or network task. */
    public void migrateManualMode() {
        android.app.job.JobScheduler jobs = app.getSystemService(android.app.job.JobScheduler.class);
        if (jobs != null) { jobs.cancel(61001); jobs.cancel(61002); }
        synchronized (LOCK) {
            if (!prefs.getBoolean("nativeManualV2", false)) {
                prefs.edit().remove("endpoint").remove("token").remove("iv").remove("interval")
                        .remove("snapshot").remove("lastError")
                        .putLong("generation", generation() + 1).putBoolean("nativeManualV2", true).commit();
            }
        }
    }
    public WidgetState state() {
        if (demo()) return demoState();
        WidgetState state = new WidgetState();
        try {
            String snapshot = prefs.getString("snapshot", "");
            if (!snapshot.isEmpty()) state = parse(new JSONObject(snapshot));
        } catch (Exception invalid) { state.error = "invalid_response"; }
        state.configured = AccountClient.isSignedIn(app);
        String error = prefs.getString("lastError", "");
        if (!error.isEmpty()) { state.error = error; state.stale = true; }
        long now = System.currentTimeMillis() / 1000;
        if ((state.weeklyResetAt > 0 && now >= state.weeklyResetAt)
                || (state.fiveHourResetAt > 0 && now >= state.fiveHourResetAt)) state.stale = true;
        return state;
    }
    public void saveSnapshot(JSONObject json, long expectedGeneration) throws Exception {
        parse(json);
        synchronized (LOCK) {
            if (generation() == expectedGeneration && !prefs.edit().putString("snapshot", json.toString())
                    .remove("lastError").commit()) throw new java.io.IOException("Snapshot could not be saved");
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
