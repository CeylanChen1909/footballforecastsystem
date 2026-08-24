package com.chen.football.news.scheduler;

import com.chen.football.news.service.NewsSourceIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时同步管理员配置的 RSS/Atom 来源；没有配置来源时安全空跑。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsSourceScheduler {

    private final NewsSourceIngestionService ingestionService;

    @Scheduled(fixedDelayString = "${content.news-sync-fixed-delay-ms:900000}")
    public void syncNewsSources() {
        try {
            var result = ingestionService.syncAll();
            if (Boolean.TRUE.equals(result.get("ok")) && ((Number) result.getOrDefault("inserted", 0)).intValue() > 0) {
                log.info("[ContentIngestion] scheduled sync inserted {} articles", result.get("inserted"));
            }
        } catch (Exception e) {
            log.warn("[ContentIngestion] scheduled sync failed: {}", e.getMessage());
        }
    }
}
