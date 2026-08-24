package com.chen.football.news.controller;

import com.chen.football.common.util.AdminGuard;
import com.chen.football.news.entity.NewsSourceTask;
import com.chen.football.news.mapper.NewsSourceTaskMapper;
import com.chen.football.news.service.NewsSourceIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 管理 RSS/Atom 来源和手动触发同步。 */
@RestController
@RequestMapping("/api/admin/content/sources")
@RequiredArgsConstructor
public class ContentSourceAdminController {

    private final NewsSourceTaskMapper taskMapper;
    private final NewsSourceIngestionService ingestionService;

    @GetMapping
    public List<NewsSourceTask> list() {
        AdminGuard.requireAdmin();
        return taskMapper.selectList(null);
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody NewsSourceTask task) {
        AdminGuard.requireAdmin();
        if (task == null || !StringUtils.hasText(task.getSourceName()) || !StringUtils.hasText(task.getSourceBaseUrl())) {
            return Map.of("ok", false, "message", "sourceName 和 sourceBaseUrl 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        if (task.getTaskType() == null || task.getTaskType().isBlank()) task.setTaskType("ARTICLE");
        if (task.getLastStatus() == null || task.getLastStatus().isBlank()) task.setLastStatus("PENDING");
        if (task.getCreatedAt() == null) task.setCreatedAt(now);
        task.setUpdatedAt(now);
        if (task.getId() == null) taskMapper.insert(task); else taskMapper.updateById(task);
        return Map.of("ok", true, "id", task.getId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        AdminGuard.requireAdmin();
        return Map.of("ok", taskMapper.deleteById(id) > 0);
    }

    @PostMapping("/{id}/sync")
    public Map<String, Object> sync(@PathVariable Long id) {
        AdminGuard.requireAdmin();
        return ingestionService.syncTask(id);
    }

    @PostMapping("/sync")
    public Map<String, Object> syncAll() {
        AdminGuard.requireAdmin();
        return ingestionService.syncAll();
    }
}
