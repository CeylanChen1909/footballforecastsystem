package com.chen.football.agent.tool;

import com.chen.football.news.entity.NewsArticle;
import com.chen.football.news.mapper.NewsArticleMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NewsContextTool implements AgentTool {

    private final NewsArticleMapper newsArticleMapper;

    public NewsContextTool(NewsArticleMapper newsArticleMapper) {
        this.newsArticleMapper = newsArticleMapper;
    }

    @Override
    public String name() {
        return "news_context";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Long articleId = toLong(context.get("articleId"));

        NewsArticle article = articleId == null ? null : newsArticleMapper.selectById(articleId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("articleId", articleId);
        data.put("article", article);
        data.put("status", article == null ? (articleId == null ? "MISSING_INPUT" : "EMPTY") : "AVAILABLE");
        data.put("message", article == null
                ? (articleId == null ? "缺少文章 ID，暂不读取资讯" : "当前没有找到该文章")
                : "已读取文章摘要与来源信息");
        if (article != null) {
            data.put("sourceUpdatedAt", article.getUpdatedAt());
        }
        return data;
    }

    private Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
