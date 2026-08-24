package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.crawler.entity.CrawlerMatchAdminOverride;
import com.chen.football.crawler.service.AdminMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController("crawlerAdminMatchController")
@RequestMapping("/api/admin/matches")
@RequiredArgsConstructor
public class AdminMatchController {
    private final AdminMatchService adminMatchService;

    @GetMapping("/overrides")
    public ApiResponse<List<CrawlerMatchAdminOverride>> listOverrides() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(adminMatchService.listOverrides());
    }

    @PostMapping("/overrides")
    public ApiResponse<Map<String, Object>> saveOverride(@RequestBody CrawlerMatchAdminOverride override) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(Map.of("ok", adminMatchService.saveOverride(override)));
    }

    @DeleteMapping("/overrides/{fixtureId}")
    public ApiResponse<Map<String, Object>> deleteOverride(@PathVariable(name = "fixtureId") Long fixtureId) {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(Map.of("ok", adminMatchService.deleteOverride(fixtureId)));
    }
}
