package com.chen.football.crawler.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chen.football.common.context.UserContext;
import com.chen.football.crawler.entity.CrawlerMatchAdminOverride;
import com.chen.football.crawler.mapper.CrawlerMatchAdminOverrideMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminMatchService {
    private final CrawlerMatchAdminOverrideMapper overrideMapper;

    public List<CrawlerMatchAdminOverride> listOverrides() {
        return overrideMapper.selectList(Wrappers.<CrawlerMatchAdminOverride>lambdaQuery().orderByDesc(CrawlerMatchAdminOverride::getUpdatedAt));
    }

    public boolean saveOverride(CrawlerMatchAdminOverride override) {
        override.setUpdatedBy(UserContext.getUserId());
        override.setUpdatedAt(LocalDateTime.now());
        CrawlerMatchAdminOverride existing = overrideMapper.selectOne(Wrappers.<CrawlerMatchAdminOverride>lambdaQuery().eq(CrawlerMatchAdminOverride::getFixtureId, override.getFixtureId()));
        if (existing == null) {
            overrideMapper.insert(override);
        } else {
            override.setId(existing.getId());
            overrideMapper.updateById(override);
        }
        return true;
    }

    public boolean deleteOverride(Long fixtureId) {
        return overrideMapper.delete(Wrappers.<CrawlerMatchAdminOverride>lambdaQuery().eq(CrawlerMatchAdminOverride::getFixtureId, fixtureId)) > 0;
    }
}
