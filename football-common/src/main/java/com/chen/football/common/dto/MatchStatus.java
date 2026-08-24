package com.chen.football.common.dto;

public final class MatchStatus {
    public static final String SCHEDULED = "NS";
    public static final String LIVE = "LIVE";
    public static final String HALF_TIME = "HT";
    public static final String FINISHED = "FT";
    public static final String POSTPONED = "PST";
    public static final String CANCELLED = "CANC";
    public static final String SUSPENDED = "SUSP";
    public static final String AWARDED = "AWD";
    /**
     * The primary source no longer returns this scheduled row in a successful
     * full-day snapshot.  It is retained for audit/history, but must not be
     * presented as a live upcoming fixture.
     */
    public static final String SOURCE_REMOVED = "SOURCE_REMOVED";
    public static final String UNKNOWN = "UNKNOWN";

    private MatchStatus() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCHEDULED;
        }
        return switch (raw.toUpperCase().trim()) {
            case "NS", "SCHEDULED", "1" -> SCHEDULED;
            case "LIVE", "IN_PLAY", "2H", "1H", "2" -> LIVE;
            case "HT", "PAUSED", "HALF_TIME" -> HALF_TIME;
            case "FT", "FINISHED", "3", "MATCH_FINISHED" -> FINISHED;
            case "PST", "POSTPONED", "4" -> POSTPONED;
            case "CANC", "CANCELLED" -> CANCELLED;
            case "SUSP", "SUSPENDED" -> SUSPENDED;
            case "AWD", "AWARDED" -> AWARDED;
            case "SOURCE_REMOVED", "REMOVED", "SOURCE-DELETED" -> SOURCE_REMOVED;
            default -> raw.toUpperCase().trim();
        };
    }

    public static boolean isFinished(String status) {
        return FINISHED.equals(status) || AWARDED.equals(status);
    }

    public static boolean isLive(String status) {
        return LIVE.equals(status) || HALF_TIME.equals(status);
    }

    public static boolean hasScore(String status) {
        return isFinished(status) || isLive(status);
    }

    public static boolean isSourceRemoved(String status) {
        return SOURCE_REMOVED.equals(normalize(status));
    }
}
