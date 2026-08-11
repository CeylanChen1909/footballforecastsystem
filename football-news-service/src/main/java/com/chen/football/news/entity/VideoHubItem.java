package com.chen.football.news.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_video_hub_item")
public class VideoHubItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String subtitle;
    private String description;
    private String coverImage;
    private String videoUrl;
    private String platform;
    private String leagueName;
    private String homeTeamName;
    private String awayTeamName;
    private LocalDateTime matchTime;
    private String videoType;
    private Integer isHot;
    private Integer isFeatured;
    private Integer sortOrder;
    private String status;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
