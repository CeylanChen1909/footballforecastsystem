package com.chen.football.news.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chen.football.news.entity.NewsArticleAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminAuditLogMapper extends BaseMapper<NewsArticleAuditLog> {
}
