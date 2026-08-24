package com.chen.football.analytics.controller;

import com.chen.football.analytics.service.AnalyticsEventService;
import com.chen.football.analytics.service.AnalyticsRateLimitService;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final AnalyticsEventService eventService;
    private final AnalyticsRateLimitService rateLimitService;

    @PostMapping("/events")
    public ApiResponse<Map<String, Object>> event(@RequestBody(required = false) Map<String, Object> body,
                                                  HttpServletRequest request) {
        if (!rateLimitService.allow(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "埋点频率过高，请稍后再试");
        }
        if (body == null || body.size() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "埋点参数无效");
        }
        String eventName = value(body.get("eventName"));
        if (eventName == null || eventName.isBlank() || eventName.length() > 64
                || !eventName.matches("[\\p{L}\\p{N}_.:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventName 无效");
        }
        Map<String, Object> properties = body.get("properties") instanceof Map<?, ?> raw
                ? raw.entrySet().stream().limit(30)
                .collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new))
                : Map.of();
        eventService.track(truncate(value(body.get("eventId")), 96), eventName, truncate(value(body.get("page")), 128),
                truncate(value(body.get("entityType")), 64), truncate(value(body.get("entityId")), 128), properties);
        return ApiResponse.ok(Map.of("accepted", true));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(defaultValue = "7") int days) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(eventService.summary(days));
    }

    private String value(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
}
