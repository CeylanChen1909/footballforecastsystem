package com.chen.football.common.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central translator for user-visible backend text.
 *
 * <p>Only text that contains Chinese is considered.  Static messages are
 * looked up first, then a small set of dynamic patterns is applied.  Unknown
 * text is returned unchanged: this keeps database-authored content (news,
 * changelog, persona catalog) intact while still localizing API-owned
 * messages.</p>
 */
public final class I18nText {

    private static final Map<String, String> MESSAGES = new LinkedHashMap<>();
    private static final List<Rule> RULES;

    static {
        // Generic responses.
        message("获取成功", "Success");
        message("获取成功（缓存）", "Success (cached)");
        message("获取阵容资料", "Squad retrieved");
        message("读取缓存的球队阵容", "Cached squad retrieved");
        message("请求失败", "Request failed");
        message("未找到比赛", "Match not found");
        message("数据同步失败", "Data sync failed");
        message("球队资料同步失败", "Team data sync failed");
        message("暂无球队资料", "No team data available");
        message("该联赛当前没有已同步的球队档案", "No synced team profiles for this league");
        message("球队资料较旧", "Team data is stale");
        message("球队资料可用", "Team data is available");
        message("暂无积分榜", "No standings available");
        message("暂无多组积分榜", "No group standings available");
        message("该赛事积分榜尚未同步；欧国联为多组赛制，请稍后重试管理员刷新，不会回落国内联赛表",
                "Standings for this competition have not synced yet. The Nations League uses multiple groups; "
                        + "please retry an admin refresh later. Domestic tables are never used as a fallback.");
        message("该联赛当前没有已同步的积分榜数据", "No synced standings are available for this league");
        message("数据较旧", "Data is stale");
        message("赛季尚未产生积分", "No points recorded yet this season");
        message("数据可用", "Data is available");
        message("积分榜超过 72 小时未更新，请谨慎参考", "Standings have not updated for over 72 hours; use them with caution");
        message("数据源额度受限", "Data source quota limited");
        message("数据源尚未覆盖", "Data source does not cover this yet");
        message("暂无已采集比赛", "No collected matches yet");
        message("已有数据，但数据源额度受限", "Data available, but the source quota is limited");
        message("已有数据，但最近同步失败", "Data available, but the latest sync failed");
        message("已有数据，但同步较旧", "Data available, but the latest sync is stale");
        message("外部数据源当前受额度或套餐限制，请稍后重试", "The external data source is currently quota or plan limited. Please try again later.");
        message("主数据源请求失败，请稍后重试", "The primary data source request failed. Please try again later.");
        message("主数据源尚未确认该日期的赛程，不能等同于没有比赛",
                "The primary data source has not confirmed fixtures for that date; this does not mean there are no matches.");
        message("当前日期暂无已采集比赛，主数据源已完成本次检查",
                "No matches have been collected for this date. The primary data source completed this check.");
        message("当前赛季尚未产生可验证积分", "no points recorded yet this season");
        message("当前窗口暂无已生成预测", "No generated predictions in this window");
        message("阵容数据源未启用", "Squad data source is not enabled");
        message("缺少球队名称", "Team name is required");
        message("球队名称无效", "Invalid team name");
        message("当前球队不在已同步资料中", "This team is not in the synced directory");
        message("当前主爬虫源未提供可映射的第三方球队编号，暂无法核验注册名单",
                "The primary crawler source has no mapped third-party team id, so the registered squad cannot be verified yet.");
        message("阵容源返回错误", "The squad source returned an error");
        message("阵容源未返回可核验的注册名单", "The squad source returned no verifiable registered squad");
        message("已加载第三方阵容数据", "Third-party squad data loaded");
        message("阵容请求失败", "Squad request failed");
        message("比赛尚未开始，暂无该类数据", "The match has not started; this data is not available yet");

        // Match status labels.
        message("未开始", "Not started");
        message("进行中", "Live");
        message("中场", "Half-time");
        message("完场", "Full-time");
        message("上半场", "First half");
        message("下半场", "Second half");
        message("取消", "Cancelled");
        message("推迟", "Postponed");
        message("赛程已变更", "Fixture changed");
        message("主队", "Home team");
        message("客队", "Away team");

        // Prediction / model fallbacks.
        message("历史样本有限，使用 ELO+Poisson 保守基线",
                "Limited historical sample; using a conservative ELO+Poisson baseline");
        message("赛前增强特征缺失，使用透明 ELO+Poisson 基线",
                "Prematch enriched features are missing; using a transparent ELO+Poisson baseline");
        message("未启用 Python 模型", "Python model is not enabled");
        message("Python 返回了无效预测结果", "Python returned an invalid prediction");
        message("数据不足，暂不生成预测。", "Insufficient data; no prediction was generated.");
        message("未找到该球队的已结束比赛", "No completed matches found for this team");
        message("比赛缺少完整比分", "Match is missing a complete score");

        // Model metadata that may already be persisted in a snapshot.
        message("单场概率经过时间滚动校准；仍建议结合数据完整度，低置信度结果不应强行解读",
                "Single-match probabilities are calibrated with rolling time validation; combine them with data completeness and do not over-interpret low-confidence results.");
        message("当前模型未显著超过基线或平局识别不足，使用透明ELO兜底",
                "The current model does not clearly beat the baseline or lacks draw recognition; a transparent ELO fallback is used.");
        message("模型通过生产门槛", "Model meets the production gate");
        message("数据源暂时跳过", "Data source skipped temporarily");
        message("日期格式错误", "Invalid date format");

        // Authentication and account messages.
        message("注册过于频繁，请稍后再试", "Too many registration attempts. Please try again later.");
        message("请输入有效邮箱", "Please enter a valid email address.");
        message("昵称不能为空且不能超过32个字符", "Nickname is required and must be at most 32 characters.");
        message("密码长度需在8-64位之间", "Password must be 8–64 characters long.");
        message("邮箱验证码错误或已过期", "The email verification code is invalid or has expired.");
        message("请先完成图形验证", "Please complete the CAPTCHA first.");
        message("登录尝试过于频繁，请稍后再试", "Too many login attempts. Please try again later.");
        message("邮箱和密码不能为空", "Email and password are required.");
        message("登录失败次数过多，请完成安全验证",
                "Too many failed login attempts. Please complete the security check.");
        message("refreshToken 不能为空", "refreshToken is required.");
        message("登录已过期，请重新登录", "Your session has expired. Please sign in again.");
        message("未登录或登录已过期", "Not signed in or session expired.");
        message("已退出其他设备", "Signed out of other devices.");
        message("协议记录尚未初始化，请先执行数据库迁移 V2026082404__user_legal_consent.sql",
                "Consent records are not initialized. Run database migration V2026082404__user_legal_consent.sql first.");
        message("协议确认无法保存，请先执行数据库迁移 V2026082404__user_legal_consent.sql",
                "Consent could not be saved. Run database migration V2026082404__user_legal_consent.sql first.");
        message("偏好项过多", "Too many preferences.");
        message("偏好内容过大", "Preferences payload is too large.");
        message("偏好保存失败", "Failed to save preferences.");
        message("通知暂时不可用", "Notifications are temporarily unavailable.");
        message("通知状态更新失败", "Failed to update notification status.");
        message("如果该邮箱已注册，验证码已发送",
                "If this email is registered, a verification code has been sent.");
        message("密码已更新", "Password updated.");
        message("用户名不能为空且不能超过32个字符", "Username is required and must be at most 32 characters.");
        message("密码长度需在6-64位之间", "Password must be 6–64 characters long.");
        message("用户名已存在", "Username already exists.");
        message("该邮箱已注册，请直接登录", "This email is already registered. Please sign in.");
        message("该昵称已被使用，请换一个昵称", "This nickname is already taken. Please choose another one.");
        message("用户名或密码不能为空", "Username and password are required.");
        message("用户名或密码错误", "Incorrect username or password.");
        message("该演示账号已停用，请使用受控账号登录",
                "This demo account is disabled. Please use a managed account.");
        message("账号已被禁用或锁定", "The account is disabled or locked.");
        message("邮箱尚未验证，请先完成邮箱验证", "Email is not verified. Please verify your email first.");
        message("密码不能为空", "Password is required.");
        message("新密码长度需在8-64位之间", "New password must be 8–64 characters long.");
        message("账号不可用", "Account is unavailable.");
        message("当前密码错误", "Current password is incorrect.");
        message("账号不存在", "Account does not exist.");
        message("昵称未改变", "Nickname unchanged.");
        message("个人资料已更新", "Profile updated.");
        message("头像已移除", "Avatar removed.");
        message("头像已更新", "Avatar updated.");
        message("昵称不能为空", "Nickname is required.");
        message("昵称不能超过32个字符", "Nickname must be at most 32 characters.");
        message("昵称不能包含控制字符", "Nickname cannot contain control characters.");
        message("昵称包含不允许使用的内容", "Nickname contains disallowed content.");
        message("头像格式不正确", "Invalid avatar format.");
        message("仅支持 PNG、JPG、JPEG 或 WebP 图片", "Only PNG, JPG, JPEG, or WebP images are supported.");
        message("头像文件过大或格式不正确", "Avatar file is too large or has an invalid format.");
        message("头像不能超过 512KB", "Avatar must not exceed 512 KB.");
        message("邮箱或验证码不正确", "Email or verification code is incorrect.");
        message("密码已重置，请使用新密码登录", "Password reset. Please sign in with your new password.");
        message("用户不存在", "User does not exist.");
        message("账号数据清理未完成，请稍后重试", "Account data cleanup did not complete. Please try again later.");
        message("账号已注销", "Account deactivated.");
        message("userId 不合法", "Invalid userId.");
        message("role 不合法", "Invalid role.");
        message("status 不合法", "Invalid status.");
        message("验证码请求过于频繁", "Too many CAPTCHA requests.");
        message("图形验证码请求过于频繁，请稍后再试", "Too many CAPTCHA requests. Please try again later.");
        message("邮箱验证功能尚未启用", "Email verification is not enabled.");
        message("验证码发送过于频繁，请 60 秒后再试", "Verification code requested too frequently. Please wait 60 seconds.");
        message("验证码请求过于频繁，请稍后再试", "Too many verification code requests. Please try again later.");
        message("该邮箱今日验证码请求已达上限，请明天再试",
                "This email has reached today's verification code limit. Please try again tomorrow.");
        message("当前网络今日验证码请求已达上限，请稍后再试",
                "This network has reached today's verification code limit. Please try again later.");
        message("邮件服务暂不可用，请稍后再试", "Email service is temporarily unavailable. Please try again later.");
        message("保存失败", "Failed to save.");
        message("删除失败", "Failed to delete.");
        message("teamId/teamName 不能为空", "teamId/teamName is required.");
        message("invalid teamId", "Invalid teamId.");
        message("fixtureId 不能为空", "fixtureId is required.");
        message("fixtureId 格式错误", "Invalid fixtureId format.");
        message("fixtureId 不能为空", "fixtureId is required.");
        message("fixtureId 格式错误", "Invalid fixtureId format.");
        message("账号不存在", "Account does not exist.");
        message("昵称每月只能修改一次", "Nicknames can only be changed once a month.");

        // Detail / other user-visible states.
        message("正常", "Normal");
        message("部分可用", "Partially available");
        message("空数据", "No data");
        message("额度受限", "Quota limited");
        message("未配置数据源", "Data source not configured");
        message("该比赛阶段不提供此数据", "This data is not available for this match stage");
        message("未检测", "Not checked");
        message("未找到基础赛程", "Base fixture not found");
        message("football-data 无响应", "football-data did not respond");
        message("基础赛程可用", "Base fixture available");
        message("football-data 请求失败", "football-data request failed");
        message("API_FOOTBALL_KEY 未配置", "API_FOOTBALL_KEY is not configured");
        message("不支持的详情类型", "Unsupported detail type");
        message("fixtureId 不能为空", "fixtureId is required.");
        message("旧版预测接口已下线，请使用比赛级预测快照",
                "The legacy prediction endpoint is retired. Use the match-level prediction snapshot.");
        message("模型请求过于频繁，请稍后再试", "Too many model requests. Please try again later.");

        // News, comments, analytics and shared operation messages.
        message("资讯不存在", "Article not found");
        message("评论不存在", "Comment not found");
        message("父评论不存在", "Parent comment not found");
        message("参数不能为空", "Parameters are required");
        message("评论长度需在 2-500 个字符之间", "Comment must be between 2 and 500 characters");
        message("评论过于频繁，请稍后再试", "Too many comments. Please try again later.");
        message("评论包含暂不允许发布的内容", "The comment contains content that is not allowed");
        message("资讯不存在或不可评论", "Article not found or comments are disabled");
        message("埋点频率过高，请稍后再试", "Too many analytics events. Please try again later.");
        message("埋点参数无效", "Invalid analytics parameters");
        message("eventName 无效", "Invalid eventName");
        message("埋点表尚未初始化或暂时不可用", "The analytics table is not initialized or temporarily unavailable");
        message("视频不存在", "Video not found");
        message("视频已保存", "Video saved");
        message("播放地址无效，已保存为草稿；补充真实播放链接后再发布",
                "The playback URL is invalid, so the video was saved as a draft. Add a working playback URL before publishing.");
        message("查询起止日期无效", "Invalid date range");
        message("相同采集任务正在执行，请稍后查看任务状态",
                "The same crawl task is already running. Check task status later.");
        message("该赛事积分榜尚未同步", "Standings for this competition have not synced yet");
        message("比赛预测暂不可用", "Match prediction is temporarily unavailable");
        message("预测状态查询失败", "Failed to query prediction status");
        message("预测快照可用", "Prediction snapshots are available");
        message("已读取比赛级预测状态", "Match-level prediction status loaded");
        message("没有需要查询预测状态的比赛", "No matches require prediction status");
        message("比赛没有可用 fixture ID", "The match has no usable fixture ID");
        message("标题不能为空", "Title is required");
        message("title 不能为空", "Title is required");
        message("参数不合法", "Invalid parameters");
        message("来源任务不存在", "Source task not found");
        message("已有资讯同步任务正在运行", "A news sync task is already running");
        message("fetchUrl 不能为空", "fetchUrl is required");
        message("读取来源失败", "Failed to read source");
        message("配置内容不能为空", "Configuration content is required");
        message("配置键不能为空", "Configuration key is required");
        message("不允许修改该配置项", "This configuration item cannot be modified");
        message("密钥类配置必须通过环境变量注入，不允许写入后台配置表",
                "Secret configuration must be injected through environment variables and cannot be stored in the admin configuration table.");
        message("写入失败", "Write failed");
        message("模型 Base URL 必须是无凭据的 http(s) 地址",
                "The model base URL must be a credential-free http(s) address");
        message("模型 Base URL 格式无效", "Invalid model base URL format");
        message("模型通道只能是 auto、deepseek、openrouter 或 scnet",
                "The model channel must be auto, deepseek, openrouter or scnet");
        message("Temperature 必须在 0 到 1.2 之间", "Temperature must be between 0 and 1.2");
        message("Temperature 必须是数字", "Temperature must be a number");
        message("最大 Tokens 必须在 128 到 2048 之间", "Max tokens must be between 128 and 2048");
        message("最大 Tokens 必须是整数", "Max tokens must be an integer");
        message("开关配置只能是 true 或 false", "Toggle configuration must be true or false");
        message("模型白名单不能包含换行符", "The model allow-list cannot contain newlines");
        message("账号数据清理未完成，请稍后重试", "Account data cleanup did not complete. Please try again later.");

        // Error codes / global handler.
        message("参数错误", "Invalid request");
        message("无权限访问", "Access denied");
        message("资源不存在", "Resource not found");
        message("资源冲突", "Resource conflict");
        message("业务处理失败", "Business processing failed");
        message("系统错误", "System error");
        message("请求方法不支持", "Method not supported");
        message("不支持的 Content-Type", "Unsupported Content-Type");

        // Dynamic messages.
        RULES = List.of(
                rule("^获取失败: (.*)$", m -> "Request failed: " + translate(m.group(1))),
                rule("^获取失败：(.*)$", m -> "Request failed: " + translate(m.group(1))),
                rule("^系统异常: (.*)$", m -> "System error: " + translate(m.group(1))),
                rule("^用户列表加载失败: (.*)$", m -> "Failed to load user list: " + translate(m.group(1))),
                rule("^请求方法不支持: (.*)$", m -> "Method not supported: " + m.group(1)),
                rule("^不支持的 Content-Type: (.*)$", m -> "Unsupported Content-Type: " + m.group(1)),
                rule("^已返回 (\\d+) 场比赛[；;](.+)$", m -> "Returned " + m.group(1) + " matches; " + translate(m.group(2))),
                rule("^已返回 (\\d+) 场比赛$", m -> "Returned " + m.group(1) + " matches"),
                rule("^已返回 (\\d+) 支球队[，,](.+)$", m -> "Returned " + m.group(1) + " teams; " + translate(m.group(2))),
                rule("^已返回 (\\d+) 支球队$", m -> "Returned " + m.group(1) + " teams"),
                rule("^已按 (\\d+) 个小组返回 (\\d+) 支球队$", m -> "Returned " + m.group(2) + " teams across " + m.group(1) + " groups"),
                rule("^已返回 (\\d+) 条预测快照$", m -> "Returned " + m.group(1) + " prediction snapshots"),
                rule("^昵称每月只能修改一次，下次可在 (.*) 后修改$", m -> "Nicknames can only be changed once a month. You can change it again after " + m.group(1) + "."),
                rule("^采集任务失败: (.*)$", m -> "Crawl task failed: " + translate(m.group(1))),
                rule("^不支持的任务类型: (.*)$", m -> "Unsupported task type: " + translate(m.group(1))),
                rule("^配置加载失败: (.*)$", m -> "Failed to load configuration: " + translate(m.group(1))),
                rule("^日志加载失败: (.*)$", m -> "Failed to load logs: " + translate(m.group(1))),
                rule("^部分配置保存失败：(.*)$", m -> "Some configuration items could not be saved: " + translate(m.group(1))),
                rule("^部分特征缺失：(.*)$", m -> "Some features are missing: " + m.group(1)),
                rule("^Python 推理服务不可用：(.*)$", m -> "Python inference service unavailable: " + m.group(1)),
                rule("^球队(\\d+)$", m -> "Team " + m.group(1))
        );
    }

    private I18nText() {
    }

    private static void message(String zh, String en) {
        MESSAGES.put(zh, en);
    }

    private static Rule rule(String regex, Function<Matcher, String> renderer) {
        return new Rule(Pattern.compile(regex, Pattern.DOTALL), renderer);
    }

    /** Returns the localized variant of {@code zh} for the active request language. */
    public static String translate(String zh) {
        return translate(LangContext.get(), zh);
    }

    /**
     * Returns the localized variant of {@code zh} for an explicitly supplied
     * language.  Worker/SSE threads that outlive the servlet request must pass
     * the language captured on the request thread instead of relying on the
     * {@link LangContext} ThreadLocal.
     */
    public static String translate(String lang, String zh) {
        if (zh == null || zh.isBlank() || LangContext.ZH.equals(LangContext.normalize(lang))
                || !containsChinese(zh)) {
            return zh;
        }
        String trimmed = zh.trim();
        String direct = MESSAGES.get(trimmed);
        if (direct != null) return direct;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(trimmed);
            if (matcher.matches()) return rule.renderer().apply(matcher);
        }
        return zh;
    }

    /** Convenience for code that already has both variants. */
    public static String of(String zh, String en) {
        return LangContext.isChinese() ? zh : en;
    }

    /** Convenience for callers that already captured the request language. */
    public static String of(String lang, String zh, String en) {
        return LangContext.ZH.equals(LangContext.normalize(lang)) ? zh : en;
    }

    public static String message(String zh) {
        return translate(zh);
    }

    public static boolean containsChinese(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    private record Rule(Pattern pattern, Function<Matcher, String> renderer) {
    }
}
