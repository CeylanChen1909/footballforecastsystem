package com.chen.football.news.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.news.entity.VideoHubItem;
import com.chen.football.news.service.VideoHubPage;
import com.chen.football.news.service.VideoHubService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/videos")
@RequiredArgsConstructor
public class AdminVideoController {

    private static final Logger log = LoggerFactory.getLogger(AdminVideoController.class);
    private final VideoHubService videoHubService;

    @org.springframework.beans.factory.annotation.Value("${app.debug-seed-enabled:false}")
    private boolean debugSeedEnabled;

    @GetMapping
    public ApiResponse<Map<String, Object>> page(@RequestParam Map<String, String> params) {
        AdminGuard.requireAdmin();
        String keyword = normalize(params.get("keyword"));
        String status = normalize(params.get("status"));
        int page = parseInt(params.get("page"), 1);
        int size = parseInt(params.get("size"), 20);
        log.info("[VideoAdmin][LIST] params={}, resolved keyword={}, status={}, page={}, size={}, userId={}, username={}",
                params, keyword, status, page, size, UserContext.getUserId(), UserContext.getUsername());
        VideoHubPage result = videoHubService.listVideosPage(keyword, status, page, size);
        log.info("[VideoAdmin][LIST][RESULT] total={}, returned={}, page={}, size={}",
                result.total(), result.items() == null ? 0 : result.items().size(), result.page(), result.size());
        return ApiResponse.ok(Map.of("items", result.items(), "total", result.total(), "page", result.page(), "size", result.size()));
    }

    @GetMapping("/{id}")
    public ApiResponse<VideoHubItem> detail(@PathVariable("id") Long id) {
        AdminGuard.requireAdmin();
        log.info("[VideoAdmin][DETAIL] id={}, userId={}, username={}", id, UserContext.getUserId(), UserContext.getUsername());
        VideoHubItem item = videoHubService.getById(id);
        log.info("[VideoAdmin][DETAIL][RESULT] found={}, title={}, status={}", item != null, item == null ? null : item.getTitle(), item == null ? null : item.getStatus());
        return ApiResponse.ok(item);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody VideoHubItem item) {
        AdminGuard.requireAdmin();
        if (item == null || !StringUtils.hasText(item.getTitle())) {
            return ApiResponse.ok(Map.of("ok", false, "message", "title 不能为空"));
        }
        log.info("[VideoAdmin][CREATE] payload={}, userId={}, username={}", item, UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> result = videoHubService.saveVideo(item, UserContext.getUserId(), UserContext.getUsername());
        log.info("[VideoAdmin][CREATE][RESULT] {}", result);
        return ApiResponse.ok(result);
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("id") Long id, @RequestBody VideoHubItem item) {
        AdminGuard.requireAdmin();
        if (item == null || id == null || id <= 0) {
            return ApiResponse.ok(Map.of("ok", false, "message", "参数不合法"));
        }
        item.setId(id);
        log.info("[VideoAdmin][UPDATE] id={}, payload={}, userId={}, username={}", id, item, UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> result = videoHubService.saveVideo(item, UserContext.getUserId(), UserContext.getUsername());
        log.info("[VideoAdmin][UPDATE][RESULT] {}", result);
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable("id") Long id) {
        AdminGuard.requireAdmin();
        log.info("[VideoAdmin][DELETE] id={}, userId={}, username={}", id, UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> result = videoHubService.deleteVideo(id, UserContext.getUserId(), UserContext.getUsername());
        log.info("[VideoAdmin][DELETE][RESULT] {}", result);
        return ApiResponse.ok(result);
    }

    @PutMapping({"/{id}/status", "/{id}/status/"})
    public ApiResponse<Map<String, Object>> status(@PathVariable("id") Long id, @RequestParam Map<String, String> params) {
        AdminGuard.requireAdmin();
        String status = normalize(params.get("status"));
        if (!List.of("DRAFT", "PUBLISHED", "HIDDEN", "ARCHIVED").contains(status.toUpperCase())) {
            return ApiResponse.ok(Map.of("ok", false, "message", "status 不合法"));
        }
        log.info("[VideoAdmin][STATUS] id={}, status={}, params={}, userId={}, username={}", id, status, params, UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> result = videoHubService.updateStatus(id, status, UserContext.getUserId(), UserContext.getUsername());
        log.info("[VideoAdmin][STATUS][RESULT] {}", result);
        return ApiResponse.ok(result);
    }

    @PostMapping("/debug/seed")
    public ApiResponse<Map<String, Object>> debugSeed() {
        AdminGuard.requireSuperAdmin();
        if (!debugSeedEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug seed disabled");
        }
        log.info("[VideoAdmin][DEBUG_SEED] userId={}, username={}", UserContext.getUserId(), UserContext.getUsername());
        Map<String, Object> result = videoHubService.saveDebugSeed(UserContext.getUserId(), UserContext.getUsername());
        log.info("[VideoAdmin][DEBUG_SEED][RESULT] {}", result);
        return ApiResponse.ok(result);
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
