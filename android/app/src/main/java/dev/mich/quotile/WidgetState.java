package dev.mich.quotile;

/** A validated server snapshot. All timestamps are Unix seconds; null means not supplied. */
public final class WidgetState {
    public Double weeklyRemaining;
    public Double fiveHourRemaining;
    /** Server-reported usable Codex reset credits; null means unavailable, not zero. */
    public Long availableResetCount;
    public long weeklyResetAt;
    public long fiveHourResetAt;
    public long updatedAt;
    public boolean stale;
    public boolean demo;
    public boolean configured;
    public String plan;
    public String error;
}
