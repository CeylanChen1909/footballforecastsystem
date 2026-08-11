-- 爬虫服务数据库表

-- 比赛数据表
CREATE TABLE IF NOT EXISTS `crawler_matches` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `external_match_id` VARCHAR(64) COMMENT '第三方平台比赛ID',
    `league_name` VARCHAR(100) COMMENT '联赛名称',
    `league_id` VARCHAR(32) COMMENT '联赛ID',
    `home_team_name` VARCHAR(100) COMMENT '主队名称',
    `home_team_id` VARCHAR(32) COMMENT '主队ID',
    `home_team_logo` VARCHAR(500) COMMENT '主队Logo',
    `away_team_name` VARCHAR(100) COMMENT '客队名称',
    `away_team_id` VARCHAR(32) COMMENT '客队ID',
    `away_team_logo` VARCHAR(500) COMMENT '客队Logo',
    `match_time` DATETIME COMMENT '比赛时间',
    `status` VARCHAR(20) DEFAULT 'NS' COMMENT '比赛状态: NS=未开始, LIVE=进行中, FT=已结束, HT=中场',
    `home_score` INT DEFAULT 0 COMMENT '主队得分',
    `away_score` INT DEFAULT 0 COMMENT '客队得分',
    `venue` VARCHAR(200) COMMENT '比赛场馆',
    `round` VARCHAR(100) COMMENT '赛事轮次',
    `source` VARCHAR(50) DEFAULT 'zq123' COMMENT '数据来源',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_match_time` (`match_time`),
    INDEX `idx_status` (`status`),
    INDEX `idx_league` (`league_name`),
    INDEX `idx_external_id` (`external_match_id`, `source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='爬取的比赛数据表';

-- 球队数据表
CREATE TABLE IF NOT EXISTS `crawler_teams` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `name` VARCHAR(100) COMMENT '球队名称',
    `logo` VARCHAR(500) COMMENT '球队Logo',
    `league_name` VARCHAR(100) COMMENT '所属联赛',
    `country` VARCHAR(50) COMMENT '国家',
    `source` VARCHAR(50) DEFAULT 'zq123' COMMENT '数据来源',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_name` (`name`),
    INDEX `idx_league` (`league_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='爬取的球队数据表';

-- 积分榜数据表
CREATE TABLE IF NOT EXISTS `crawler_standings` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    `league_name` VARCHAR(100) COMMENT '联赛名称',
    `league_id` VARCHAR(32) COMMENT '联赛ID',
    `season` VARCHAR(20) COMMENT '赛季，如 2023/2024',
    `team_name` VARCHAR(100) COMMENT '球队名称',
    `team_id` VARCHAR(32) COMMENT '球队ID',
    `team_logo` VARCHAR(500) COMMENT '球队Logo',
    `rank` INT COMMENT '排名',
    `played` INT DEFAULT 0 COMMENT '场次',
    `wins` INT DEFAULT 0 COMMENT '胜',
    `draws` INT DEFAULT 0 COMMENT '平',
    `losses` INT DEFAULT 0 COMMENT '负',
    `goals_for` INT DEFAULT 0 COMMENT '进球',
    `goals_against` INT DEFAULT 0 COMMENT '失球',
    `goal_difference` INT DEFAULT 0 COMMENT '净胜球',
    `points` INT DEFAULT 0 COMMENT '积分',
    `source` VARCHAR(50) DEFAULT 'zq123' COMMENT '数据来源',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_league_season` (`league_id`, `season`),
    INDEX `idx_rank` (`rank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='爬取的积分榜数据表';
