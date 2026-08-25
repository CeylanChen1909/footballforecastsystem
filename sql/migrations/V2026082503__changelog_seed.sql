INSERT INTO t_changelog (title, summary, details_text, tag, tone, version_label, status, publish_at)
SELECT '注册与账户资料体验更新', '优化邮箱验证码校验、头像同步和球队关注状态反馈。', '验证码失败不会清空已填写内容\n个人资料头像会同步到顶部导航', '账户', 'account', NULL, 'PUBLISHED', '2026-08-25 10:00:00'
WHERE NOT EXISTS (SELECT 1 FROM t_changelog WHERE title = '注册与账户资料体验更新');

INSERT INTO t_changelog (title, summary, details_text, tag, tone, version_label, status, publish_at)
SELECT '比赛焦点改为动态推荐', '焦点比赛根据联赛、开赛距离和球队热度计算，不再绑定某个日期。', '左侧焦点栏可快速打开比赛详情\n球队中文名、英文名支持切换和模糊搜索', '赛程', 'match', NULL, 'PUBLISHED', '2026-08-24 10:00:00'
WHERE NOT EXISTS (SELECT 1 FROM t_changelog WHERE title = '比赛焦点改为动态推荐');

INSERT INTO t_changelog (title, summary, details_text, tag, tone, version_label, status, publish_at)
SELECT '预测状态表达更清晰', '区分已生成、生成中、数据不足和暂不可用，减少状态误读。', '比赛时间和赛果状态统一按北京时间展示\n数据源延迟时保留明确的更新时间提示', '预测', 'prediction', NULL, 'PUBLISHED', '2026-08-23 10:00:00'
WHERE NOT EXISTS (SELECT 1 FROM t_changelog WHERE title = '预测状态表达更清晰');
