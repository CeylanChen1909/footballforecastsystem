package com.chen.football.changelog.controller;

import com.chen.football.changelog.service.ChangelogService;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ChangelogController {
    private final ChangelogService changelogService;

    @GetMapping("/api/changelog")
    public ApiResponse<List<Map<String, Object>>> published(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(changelogService.listPublished(limit));
    }

    @GetMapping("/api/admin/changelog")
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String status) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(changelogService.listAdmin(keyword, status, page, size));
    }

    @PostMapping("/api/admin/changelog")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(changelogService.create(payload));
    }

    @PutMapping("/api/admin/changelog/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(changelogService.update(id, payload));
    }

    @PutMapping("/api/admin/changelog/{id}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable long id, @RequestParam String status) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(changelogService.updateStatus(id, status));
    }

    @DeleteMapping("/api/admin/changelog/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(changelogService.delete(id));
    }
}
