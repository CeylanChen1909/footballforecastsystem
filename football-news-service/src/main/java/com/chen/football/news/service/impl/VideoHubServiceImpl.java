package com.chen.football.news.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.football.news.entity.VideoHubItem;
import com.chen.football.news.mapper.VideoHubItemMapper;
import com.chen.football.news.service.VideoHubPage;
import com.chen.football.news.service.VideoHubService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VideoHubServiceImpl implements VideoHubService {

    private static final Logger log = LoggerFactory.getLogger(VideoHubServiceImpl.class);
    private final VideoHubItemMapper videoHubItemMapper;

    @Override
    public List<VideoHubItem> listVideos(String keyword, String status) {
        log.info("[VideoService][LIST] keyword={}, status={}", keyword, status);
        List<VideoHubItem> rows = videoHubItemMapper.selectList(buildWrapper(keyword, status, false).orderByDesc(VideoHubItem::getSortOrder).orderByDesc(VideoHubItem::getCreatedAt));
        log.info("[VideoService][LIST][RESULT] count={}", rows == null ? 0 : rows.size());
        return rows;
    }

    @Override
    public VideoHubPage listVideosPage(String keyword, String status, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        log.info("[VideoService][PAGE] keyword={}, status={}, page={}, size={}", keyword, status, p, s);
        Page<VideoHubItem> pg = new Page<>(p, s);
        var wrapper = buildWrapper(keyword, status, true).orderByDesc(VideoHubItem::getSortOrder).orderByDesc(VideoHubItem::getCreatedAt);
        Page<VideoHubItem> res = videoHubItemMapper.selectPage(pg, wrapper);
        VideoHubPage result = new VideoHubPage(res.getRecords(), res.getTotal(), (int) res.getCurrent(), (int) res.getSize());
        log.info("[VideoService][PAGE][RESULT] total={}, returned={}", result.total(), result.items() == null ? 0 : result.items().size());
        return result;
    }

    @Override
    public VideoHubItem getById(Long id) {
        log.info("[VideoService][DETAIL] id={}", id);
        VideoHubItem item = videoHubItemMapper.selectById(id);
        log.info("[VideoService][DETAIL][RESULT] found={}, title={}, status={}", item != null, item == null ? null : item.getTitle(), item == null ? null : item.getStatus());
        return item;
    }

    @Override
    public Map<String, Object> saveVideo(VideoHubItem item, Long userId, String username) {
        boolean isNew = item.getId() == null;
        log.info("[VideoService][{}] incoming item={}, userId={}, username={}", isNew ? "CREATE" : "UPDATE", item, userId, username);
        if (item.getStatus() == null || item.getStatus().isBlank()) item.setStatus("PUBLISHED");
        if (item.getVideoType() == null || item.getVideoType().isBlank()) item.setVideoType("HIGHLIGHT");
        if (item.getPlatform() == null) item.setPlatform("Unknown");
        if (item.getTitle() == null) item.setTitle("");
        if (item.getCoverImage() == null) item.setCoverImage("");
        if (item.getVideoUrl() == null) item.setVideoUrl("");
        if (item.getSortOrder() == null) item.setSortOrder(0);
        if (item.getIsHot() == null) item.setIsHot(0);
        if (item.getIsFeatured() == null) item.setIsFeatured(0);
        if (isNew) {
            item.setCreatedBy(userId);
            item.setCreatedAt(LocalDateTime.now());
        }
        item.setUpdatedBy(userId);
        item.setUpdatedAt(LocalDateTime.now());
        int affected = isNew ? videoHubItemMapper.insert(item) : videoHubItemMapper.updateById(item);
        log.info("[VideoService][{}][RESULT] affected={}, id={}", isNew ? "CREATE" : "UPDATE", affected, item.getId());
        return Map.of("ok", affected > 0, "id", item.getId(), "isNew", isNew, "affected", affected);
    }

    @Override
    public Map<String, Object> deleteVideo(Long id, Long userId, String username) {
        log.info("[VideoService][DELETE] id={}, userId={}, username={}", id, userId, username);
        int affected = videoHubItemMapper.deleteById(id);
        log.info("[VideoService][DELETE][RESULT] affected={}", affected);
        return Map.of("ok", affected > 0, "affected", affected);
    }

    @Override
    public Map<String, Object> updateStatus(Long id, String status, Long userId, String username) {
        log.info("[VideoService][STATUS] id={}, status={}, userId={}, username={}", id, status, userId, username);
        VideoHubItem item = videoHubItemMapper.selectById(id);
        if (item == null) {
            log.warn("[VideoService][STATUS] video not found, id={}", id);
            return Map.of("ok", false, "message", "视频不存在");
        }
        item.setStatus(status);
        item.setUpdatedBy(userId);
        item.setUpdatedAt(LocalDateTime.now());
        int affected = videoHubItemMapper.updateById(item);
        log.info("[VideoService][STATUS][RESULT] affected={}, id={}, status={}", affected, id, status);
        return Map.of("ok", affected > 0, "affected", affected);
    }

    @Override
    public List<VideoHubItem> listPublicVideos(String keyword, String leagueName, String platform, String videoType, Integer limit) {
        int l = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
        log.info("[VideoService][PUBLIC_LIST] keyword={}, leagueName={}, platform={}, videoType={}, limit={}", keyword, leagueName, platform, videoType, l);
        List<VideoHubItem> rows = videoHubItemMapper.selectList(new LambdaQueryWrapper<VideoHubItem>()
                .eq(VideoHubItem::getStatus, "PUBLISHED")
                .like(StringUtils.hasText(keyword), VideoHubItem::getTitle, keyword)
                .eq(StringUtils.hasText(leagueName), VideoHubItem::getLeagueName, leagueName)
                .eq(StringUtils.hasText(platform), VideoHubItem::getPlatform, platform)
                .eq(StringUtils.hasText(videoType), VideoHubItem::getVideoType, videoType)
                .orderByDesc(VideoHubItem::getIsFeatured)
                .orderByDesc(VideoHubItem::getIsHot)
                .orderByDesc(VideoHubItem::getSortOrder)
                .orderByDesc(VideoHubItem::getCreatedAt)
                .last("LIMIT " + l));
        log.info("[VideoService][PUBLIC_LIST][RESULT] count={}", rows == null ? 0 : rows.size());
        return rows;
    }

    @Override
    public Map<String, Object> saveDebugSeed(Long userId, String username) {
        VideoHubItem item = new VideoHubItem();
        item.setTitle("测试视频");
        item.setSubtitle("debug seed");
        item.setDescription("用于验证视频模块接口是否打通");
        item.setCoverImage("https://images.unsplash.com/photo-1518091043644-c1d4457512c6?auto=format&fit=crop&w=1200&q=80");
        item.setVideoUrl("https://www.youtube.com/");
        item.setPlatform("YouTube");
        item.setLeagueName("英超");
        item.setHomeTeamName("曼城");
        item.setAwayTeamName("利物浦");
        item.setVideoType("HIGHLIGHT");
        item.setStatus("PUBLISHED");
        item.setIsHot(1);
        item.setIsFeatured(1);
        item.setSortOrder(999);
        item.setCreatedBy(userId);
        item.setUpdatedBy(userId);
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        int affected = videoHubItemMapper.insert(item);
        log.info("[VideoService][SEED][RESULT] affected={}, id={}", affected, item.getId());
        return Map.of("ok", affected > 0, "id", item.getId(), "affected", affected);
    }

    private LambdaQueryWrapper<VideoHubItem> buildWrapper(String keyword, String status, boolean ignoreNull) {
        return new LambdaQueryWrapper<VideoHubItem>()
                .like(StringUtils.hasText(keyword), VideoHubItem::getTitle, keyword)
                .eq(StringUtils.hasText(status), VideoHubItem::getStatus, status);
    }
}
