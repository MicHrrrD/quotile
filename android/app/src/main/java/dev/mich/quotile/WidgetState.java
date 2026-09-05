package dev.mich.quotile;

/** A validated server snapshot. All timestamps are Unix seconds; null means not supplied. */
public final class WidgetState {
    public Double weeklyRemaining;
    public Double fiveHourRemaining;
    public long weeklyResetAt;
    public long fiveHourResetAt;
    public long updatedAt;
    public boolean stale;
    public boolean demo;
    public boolean configured;
    public String plan;
    public String error;
}
