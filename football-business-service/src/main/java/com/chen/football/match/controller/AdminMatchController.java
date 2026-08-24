package com.chen.football.match.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.service.AdminAuditService;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.match.entity.MatchAdminOverride;
import com.chen.football.match.mapper.MatchAdminOverrideMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/matches")
@RequiredArgsConstructor
public class AdminMatchController {

    private final MatchAdminOverrideMapper overrideMapper;
    private final AdminAuditService auditService;

    @GetMapping
    public ApiResponse<List<MatchAdminOverride>> list() {
        AdminGuard.requireAdmin();
        return ApiResponse.ok(overrideMapper.selectList(Wrappers.lambdaQuery()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody MatchAdminOverride body) {
        AdminGuard.requireAdmin();
        body.setUpdatedBy(UserContext.getUserId());
        body.setUpdatedAt(LocalDateTime.now());
        if (body.getId() == null) {
            overrideMapper.insert(body);
        } else {
            overrideMapper.updateById(body);
        }
        auditService.record("MATCH", "SAVE", "t_match_admin_override", String.valueOf(body.getFixtureId()), body.toString(), "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{fixtureId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "fixtureId") Long fixtureId) {
        AdminGuard.requireAdmin();
        overrideMapper.delete(Wrappers.<MatchAdminOverride>lambdaQuery().eq(MatchAdminOverride::getFixtureId, fixtureId));
        auditService.record("MATCH", "DELETE", "t_match_admin_override", String.valueOf(fixtureId), null, "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PutMapping("/{fixtureId}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable(name = "fixtureId") Long fixtureId, @RequestParam(name = "status") String status) {
        AdminGuard.requireAdmin();
        MatchAdminOverride row = overrideMapper.selectOne(Wrappers.<MatchAdminOverride>lambdaQuery().eq(MatchAdminOverride::getFixtureId, fixtureId));
        if (row != null) {
            row.setStatus(status);
            row.setUpdatedBy(UserContext.getUserId());
            row.setUpdatedAt(LocalDateTime.now());
            overrideMapper.updateById(row);
        }
        auditService.record("MATCH", "STATUS", "t_match_admin_override", String.valueOf(fixtureId), "status=" + status, "SUCCESS");
        return ApiResponse.ok(Map.of("ok", true));
    }
}
