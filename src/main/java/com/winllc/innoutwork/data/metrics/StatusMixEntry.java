package com.winllc.innoutwork.data.metrics;

/**
 * One category of the status mix.
 *
 * @param key   {@link #CHECKED_IN}, {@link #UNACCOUNTED}, or a {@code UserStatusEnum} name
 * @param label what the page shows
 * @param count users in the category
 */
public record StatusMixEntry(String key, String label, int count) {

    public static final String CHECKED_IN = "CHECKED_IN";
    public static final String UNACCOUNTED = "UNACCOUNTED";

    /** Tabler background colour for the bar segment and legend dot. */
    public String colorClass() {
        return switch (key) {
            case CHECKED_IN -> "bg-green";
            case UNACCOUNTED -> "bg-red";
            case "WORK_FROM_HOME" -> "bg-blue";
            case "TDY" -> "bg-purple";
            case "OUT_OF_OFFICE", "SCHEDULED_LEAVE", "ABSENT_EXCUSED" -> "bg-azure";
            case "LATE_ARRIVAL", "EARLY_LEAVE" -> "bg-yellow";
            default -> "bg-orange";
        };
    }

    /** Share of {@code total}, for the width of the bar segment. */
    public double percentOf(int total) {
        return total == 0 ? 0 : count * 100.0 / total;
    }
}
