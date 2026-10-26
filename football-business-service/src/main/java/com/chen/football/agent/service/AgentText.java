package com.chen.football.agent.service;

import com.chen.football.common.i18n.I18nText;
import com.chen.football.common.i18n.LabelCatalog;
import com.chen.football.common.i18n.LangContext;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit-language copy for the Agent API.
 *
 * <p>The servlet {@link LangContext} ThreadLocal is not available on model/tool
 * worker threads or on SSE subscription threads.  Every asynchronous entry
 * point therefore captures the request language and passes it here instead of
 * relying on the ThreadLocal.</p>
 */
public final class AgentText {

    private static final String NEW_SESSION_ZH = "新会话";
    private static final String NEW_SESSION_EN = "New session";

    private static final Map<String, String> EN = new LinkedHashMap<>();
    private static final Map<String, String> SOURCE_EN = new LinkedHashMap<>();
    private static final Map<String, String> STATUS_EN = new LinkedHashMap<>();
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();
    private static final Map<String, String> SOURCE_ZH = new LinkedHashMap<>();

    static {
        SOURCE_ZH.put("match_context", "本地赛程数据库");
        SOURCE_ZH.put("prematch_data_context", "API-Football 赛前详情缓存 + Understat 历史 xG");
        SOURCE_ZH.put("prediction", "赛前预测服务");
        SOURCE_ZH.put("team_context", "球队资料数据库");
        SOURCE_ZH.put("squad_context", "公开球队阵容源");
        SOURCE_ZH.put("news_context", "赛事资讯数据库");
        SOURCE_ZH.put("crawler_summary", "主爬虫赛程源");
        SOURCE_ZH.put("agent_summary", "Agent 运行状态");

        // Data-source labels.
        source("match_context", "本地赛程数据库", "Local fixture database");
        source("prematch_data_context", "API-Football 赛前详情缓存 + Understat 历史 xG",
                "API-Football prematch cache + Understat historical xG");
        source("prematch_data_context", "API-Football 赛前详情缓存 + Understat xG",
                "API-Football prematch cache + Understat xG");
        source("prematch_data_context", "Understat/API-Football 历史快照",
                "Understat/API-Football historical snapshot");
        source("prediction", "赛前预测服务", "Prematch prediction service");
        source("team_context", "球队资料数据库", "Team profile database");
        source("squad_context", "公开球队阵容源", "Public squad source");
        source("news_context", "赛事资讯数据库", "Match news database");
        source("news_context", "赛事资讯 RSS 摘要", "Match news RSS summaries");
        source("crawler_summary", "主爬虫赛程源", "Primary crawler fixture source");
        source("agent_summary", "Agent 运行状态", "Agent runtime status");
        source("prediction", "预测模型", "Prediction model");
        source("team_context", "近期赛程", "Recent fixtures");
        source("team_context", "球队资料", "Team profile");
        source("match_context", "赛程与赛事上下文", "Fixture and match context");
        source("news_context", "来源原文链接", "Original source links");
        source("general", "ChenFootball 当前数据集", "ChenFootball current dataset");

        // Tool result messages.
        message("已读取比赛及球队上下文", "Match and team context loaded");
        message("缺少比赛双方，暂不查询比赛资料", "Both teams are required to read match data");
        message("已识别双方，但当前没有可用比赛记录",
                "Both teams were recognised, but no usable fixture record is available");
        message("缺少文章 ID，暂不读取资讯", "An article ID is required to read news");
        message("当前没有找到该文章", "Article not found");
        message("已读取文章摘要与来源信息", "Article summary and source information loaded");
        message("缺少球队名称，暂不查询球队名单", "A team name is required to read the squad");
        message("当前没有可核验的球队名单", "No verifiable squad is available");
        message("缺少球队名称，暂不查询球队资料", "A team name is required to read team data");
        message("已读取球队资料，但双方暂无已完赛近期记录",
                "Team data loaded, but neither side has completed recent matches");
        message("已读取球队资料与已完赛近期记录",
                "Team data and completed recent matches loaded");
        message("主队资料可用，但对比球队暂无已完赛近期记录",
                "Home-team data is available, but the comparison team has no completed recent matches");
        message("已识别球队，但当前数据库没有可用资料",
                "The team was recognised, but the database has no usable data");
        message("已读取服务端预测结果", "Server prediction loaded");
        message("预测特征不足，暂不输出结论",
                "Prediction features are insufficient, so no conclusion is produced yet");
        message("缺少具体比赛，无法读取赛前数据",
                "A specific fixture is required to read prematch data");
        message("未找到该比赛的本地记录", "No local record was found for this match");
        message("已读取可核验的赛前增强数据", "Verifiable prematch enriched data loaded");
        message("已读取部分赛前数据，仍有字段缺失",
                "Partial prematch data loaded; some fields are still missing");
        message("赛前数据源请求失败或额度受限，未使用猜测值",
                "The prematch data source failed or is quota limited; no guessed values were used");
        message("当前没有可核验的赛前增强数据",
                "No verifiable prematch enriched data is available");
        message("当前时间窗口内没有可靠赛程", "No reliable fixtures in the current time window");
        message("当前筛选条件下没有可靠赛程", "No reliable fixtures under the current filters");
        message("已读取指定时间窗口内赛程", "Fixtures loaded for the requested time window");
        message("已读取今天及未来 7 天赛程", "Fixtures loaded for today and the next 7 days");
        message("预测状态查询失败", "Prediction status query failed");
        message("尚未生成比赛级预测快照", "No match-level prediction snapshot has been generated yet");
        message("缺少服务端确认的比赛上下文，拒绝使用客户端传入的概率",
                "Server-confirmed match context is missing; client-supplied probabilities were rejected");
        message("这是两队近期历史 xG 均值，不是目标比赛的赛后 xG",
                "These are recent historical xG averages for both teams, not the target match's post-match xG");
        message("未知球队", "Unknown team");
        message("未知球员", "Unknown player");
        message("该球队", "this team");

        // Evidence / quality copy.
        message("关键工具均返回可用数据", "All key tools returned usable data");
        message("仍有数据源未能提供完整事实",
                "Some data sources could not provide complete facts");
        message("数据源已响应，但当前没有可核验记录",
                "The data source responded, but no verifiable records are available");
        message("缺少查询条件，无法读取该数据",
                "Missing query parameters; this data cannot be read");
        message("只有过期数据，不能视为当前状态",
                "Only stale data is available and it cannot be treated as current");
        message("当前联赛或数据源尚未配置",
                "This competition or data source is not configured");
        message("数据源额度受限，暂时无法刷新",
                "The data source is quota limited and cannot be refreshed right now");
        message("数据源请求失败", "Data source request failed");
        message("工具因熔断或资源限制未执行",
                "The tool was skipped because of the circuit breaker or resource limits");
        message("该数据暂时无法核验", "This data cannot be verified right now");

        // Chat/fallback/action copy.
        message("暂时无法生成回复，请稍后重试。",
                "Unable to generate a reply right now. Please try again later.");
        message("主队", "Home team");
        message("客队", "Away team");
        message("打开比赛", "Open match");
        message("查看球队阵容", "View squad");
        message("推理摘要已截断", "[Reasoning summary truncated]");
        message("模型推理摘要", "Model reasoning summary");
        message("Agent 处理失败", "Agent processing failed");
        message("Agent 当前请求较多，请稍后重试",
                "Too many Agent requests right now. Please try again later.");
        message("模型返回为空", "The model returned no content");
        message("模型未返回可解析内容", "The model returned no parseable content");
        message("建议补充更多信息后再做决策。",
                "Add more information before making a decision.");
        message("检测到多个可能的球队，请先选择具体球队",
                "Multiple possible teams were detected; please choose a specific team first");
        message("服务端预测状态核验：", "Server prediction status check: ");
        message("不能将这批比赛统一标记为‘预测未生成’，请以每场记录的 predictionStatus 为准。",
                "These matches must not all be labelled as \"prediction not generated\"; use each match's predictionStatus.");
        message("根据公开球队阵容源，", "According to the public squad source, ");
        message("当前可核验到", "has");
        message("名球员：", "verifiable players:");
        message("以上为返回的部分名单，不能视为完整注册名单。",
                "The list above is partial and must not be treated as the full registered squad.");

        // Controller errors.
        message("分析请求不能为空", "Analysis request is required.");
        message("分析问题不能超过 4000 个字符",
                "The analysis question must not exceed 4000 characters.");
        message("Agent 当前正在处理较多请求，请稍后重试",
                "The assistant is handling many requests. Please try again later.");
        message("Agent 请求过于频繁，请一分钟后再试",
                "Too many assistant requests. Please try again in a minute.");
        message("请输入 Agent 问题", "Please enter an assistant question.");
        message("Agent 问题不能超过 4000 个字符",
                "The assistant question must not exceed 4000 characters.");
        message("会话 ID 无效", "Invalid session ID.");
        message("Agent 流不存在或无权取消",
                "Assistant stream not found or you are not allowed to cancel it.");
        message("Agent 今日模型额度已用尽，请明日再试",
                "Today's assistant model quota is exhausted. Please try again tomorrow.");
        message("无权访问该 Agent 会话", "You do not have access to this assistant session.");
        message("Agent 会话不存在", "Assistant session not found.");
        message("会话标题不能为空", "Session title is required.");

        // Capability descriptions.
        message("读取 Agent 与主数据源运行状态",
                "Read assistant and primary data-source status");
        message("查询今天及未来 7 天赛程",
                "Query fixtures for today and the next 7 days");
        message("读取比赛、近期赛果和交锋上下文",
                "Read match, recent results and head-to-head context");
        message("读取伤停、首发状态与历史 xG 赛前快照",
                "Read injuries, lineups and historical xG prematch snapshots");
        message("读取服务端统一预测结果", "Read the server's unified prediction");
        message("读取球队资料和近期比赛", "Read team profiles and recent matches");
        message("读取球队注册阵容与球员位置",
                "Read registered squads and player positions");
        message("读取赛事资讯摘要", "Read match news summaries");
        message("足球业务数据工具", "Football data tool");

        // Answer-validator copy.
        message("已生成", "generated");
        message("预测不可用", "prediction unavailable");
        message("生成中", "generating");
        message("生成失败", "generation failed");
        message("尚未生成", "not generated yet");
        message("状态查询失败", "status query failed");
        message("不能将这批比赛统一标记为‘预测未生成’，请以每场记录的 predictionStatus 为准。",
                "These matches must not all be labelled as \"prediction not generated\"; use each match's predictionStatus.");
        message("本次‘接下来24小时’窗口按", "This \"next 24 hours\" window is calculated in");
        message("计算。", ".");
        message("场", "matches");

        // Prompt/fixed truncation notes.
        message("分析提示已截断，不能据此推断未展示的事实",
                "The analysis prompt was truncated; do not infer facts that are not shown");
        message("该数据段已截断，不能据此推断未展示的记录",
                "This data section was truncated; do not infer records that are not shown");
        message("工具执行被中断", "Tool execution was interrupted");

        // Provider status enums (the raw Chinese values stay unchanged for compatibility).
        status("正常", "Normal");
        status("未验证", "Not verified");
        status("未配置", "Not configured");
        status("额度受限", "Quota limited");
        status("请求失败", "Request failed");
        status("部分可用", "Partially available");
        status("空数据", "No data");
        status("AVAILABLE", "Available");
        status("PARTIAL", "Partial");
        status("EMPTY", "No data");
        status("STALE", "Stale");
        status("CONFLICT", "Context conflict");
        status("NOT_CONFIGURED", "Not configured");
        status("QUOTA_LIMITED", "Quota limited");
        status("REQUEST_FAILED", "Request failed");
        status("MISSING_INPUT", "Missing input");
        status("SKIPPED", "Skipped");
        status("DISABLED", "Disabled");

        rule("^选择 (.+)$", "Select $1");
        rule("^请查询 (.+) 的相关资料$", "Look up information about $1");
        rule("^已生成 (\\d+) 场$", "$1 generated");
        rule("^预测不可用 (\\d+) 场$", "$1 prediction unavailable");
        rule("^生成中 (\\d+) 场$", "$1 generating");
        rule("^生成失败 (\\d+) 场$", "$1 generation failed");
        rule("^尚未生成 (\\d+) 场$", "$1 not generated yet");
        rule("^状态查询失败 (\\d+) 场$", "$1 status query failed");
        rule("^工具执行超时 \\((\\d+)ms\\)$", "Tool execution timed out ($1ms)");
    }

    private AgentText() {
    }

    private static void message(String zh, String en) {
        EN.put(zh, en);
    }

    private static void source(String tool, String zh, String en) {
        SOURCE_EN.put(tool, en);
        message(zh, en);
    }

    private static void status(String zhOrEnum, String en) {
        STATUS_EN.put(zhOrEnum, en);
        message(zhOrEnum, en);
    }

    private static void rule(String regex, String replacement) {
        RULES.put(regex, Pattern.compile(regex));
        EN.put("rule:" + regex, replacement);
    }

    /** Resolves an explicit language, falling back to the request ThreadLocal. */
    public static String resolve(String lang) {
        if (lang == null || lang.isBlank()) return LangContext.get();
        return LangContext.normalize(lang);
    }

    public static boolean isEnglish(String lang) {
        return LangContext.EN.equals(resolve(lang));
    }

    public static String of(String lang, String zh, String en) {
        return isEnglish(lang) ? en : zh;
    }

    /** Translates Agent-owned fixed copy for an explicitly captured language. */
    public static String translate(String lang, String zh) {
        if (zh == null || zh.isBlank() || !isEnglish(lang)) return zh;
        String trimmed = zh.trim();
        String direct = EN.get(trimmed);
        if (direct != null) return direct;
        for (Map.Entry<String, Pattern> entry : RULES.entrySet()) {
            Matcher matcher = entry.getValue().matcher(trimmed);
            if (matcher.matches()) {
                return matcher.replaceFirst(EN.get("rule:" + entry.getKey()));
            }
        }
        // Fall back to the shared catalogue for crawler/business messages.
        return I18nText.translate(lang, zh);
    }

    /** Locates a tool's data-source label in the active language. */
    public static String sourceLabel(String lang, String tool) {
        if (isEnglish(lang)) {
            String english = SOURCE_EN.get(tool);
            if (english != null) return english;
        }
        return translate(lang, SOURCE_ZH.getOrDefault(tool, tool));
    }

    /** Human label for the stable tool/status enum without changing the enum itself. */
    public static String statusLabel(String lang, String status) {
        if (status == null || status.isBlank()) return status;
        String value = status.trim();
        if (!isEnglish(lang)) return value;
        String english = STATUS_EN.get(value.toUpperCase(Locale.ROOT));
        if (english != null) return english;
        english = STATUS_EN.get(value);
        return english == null ? value : english;
    }

    /** Canonical English team name in English mode, unchanged otherwise. */
    public static String teamName(String lang, String value) {
        if (!isEnglish(lang) || value == null || value.isBlank()) return value;
        return LabelCatalog.teamEnglish(value);
    }

    /** Canonical English competition name in English mode, unchanged otherwise. */
    public static String leagueName(String lang, String value) {
        if (!isEnglish(lang) || value == null || value.isBlank()) return value;
        return LabelCatalog.leagueDisplay(value);
    }

    public static String defaultSessionTitle(String lang) {
        return of(lang, NEW_SESSION_ZH, NEW_SESSION_EN);
    }

    /** Localizes the persisted default title without changing user-defined titles. */
    public static String sessionTitle(String lang, String title) {
        if (title == null || title.isBlank()) return defaultSessionTitle(lang);
        String trimmed = title.trim();
        if (NEW_SESSION_ZH.equals(trimmed)) return of(lang, NEW_SESSION_ZH, NEW_SESSION_EN);
        if (NEW_SESSION_EN.equalsIgnoreCase(trimmed)) return of(lang, NEW_SESSION_ZH, NEW_SESSION_EN);
        return title;
    }
}
