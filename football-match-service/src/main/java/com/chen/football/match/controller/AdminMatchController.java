package com.chen.football.match.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
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

    @GetMapping
    public ApiResponse<List<MatchAdminOverride>> list() {
        return ApiResponse.ok(overrideMapper.selectList(Wrappers.lambdaQuery()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody MatchAdminOverride body) {
        body.setUpdatedBy(UserContext.getUserId());
        body.setUpdatedAt(LocalDateTime.now());
        if (body.getId() == null) {
            overrideMapper.insert(body);
        } else {
            overrideMapper.updateById(body);
        }
        return ApiResponse.ok(Map.of("ok", true));
    }

    @DeleteMapping("/{fixtureId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable(name = "fixtureId") Long fixtureId) {
        overrideMapper.delete(Wrappers.<MatchAdminOverride>lambdaQuery().eq(MatchAdminOverride::getFixtureId, fixtureId));
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PutMapping("/{fixtureId}/status")
    public ApiResponse<Map<String, Object>> status(@PathVariable(name = "fixtureId") Long fixtureId, @RequestParam(name = "status") String status) {
        MatchAdminOverride row = overrideMapper.selectOne(Wrappers.<MatchAdminOverride>lambdaQuery().eq(MatchAdminOverride::getFixtureId, fixtureId));
        if (row != null) {
            row.setStatus(status);
            row.setUpdatedBy(UserContext.getUserId());
            row.setUpdatedAt(LocalDateTime.now());
            overrideMapper.updateById(row);
        }
        return ApiResponse.ok(Map.of("ok", true));
    }
}
