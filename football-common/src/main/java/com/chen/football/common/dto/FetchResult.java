package com.chen.football.common.dto;

import java.util.List;

public record FetchResult(
        String source,
        List<NormalizedMatch> matches,
        boolean success,
        String error,
        long latencyMs
) {
    public static FetchResult success(String source, List<NormalizedMatch> matches, long latencyMs) {
        return new FetchResult(source, matches, true, null, latencyMs);
    }

    public static FetchResult failure(String source, String error, long latencyMs) {
        return new FetchResult(source, List.of(), false, error, latencyMs);
    }
}
