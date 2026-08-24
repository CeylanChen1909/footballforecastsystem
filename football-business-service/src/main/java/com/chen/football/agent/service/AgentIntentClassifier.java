package com.chen.football.agent.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AgentIntentClassifier {

    private record IntentRule(String intent, List<Pattern> patterns) {
        boolean matches(String text) {
            for (Pattern p : patterns) {
                if (p.matcher(text).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<IntentRule> rules = List.of(
            new IntentRule("schedule", List.of(
                    Pattern.compile("(?i)今天|明天|赛程|比赛安排|有赛程|fixture\\s*(?:list|schedule)|schedule"),
                    Pattern.compile("(?i)随机.*(?:比赛|球队)|(?:比赛|球队).*随机"),
                    Pattern.compile("(?i)(接下来|未来|后续|今后)\\s*\\d+\\s*(?:小时|天)|(?:列出|整理|关注).*(?:比赛|赛程)")
            )),
            new IntentRule("match-analysis", List.of(
                    Pattern.compile("(?i)比赛|赛事|对阵|fixture|match\\s*(?:analysis|prediction)"),
                    Pattern.compile("(?i)vs\\.?|对阵|交锋"),
                    Pattern.compile("(?i)主队|客队|主场|客场|home\\s*team|away\\s*team")
            )),
            new IntentRule("team-roster", List.of(
                    Pattern.compile("(?i)球员|队员|名单|阵容|首发|替补|squad|roster|lineup|players")
            )),
            new IntentRule("team-analysis", List.of(
                    Pattern.compile("(?i)球队|队|team|squad|roster"),
                    Pattern.compile("(?i)阵容|伤病|转会|transfer|injury"),
                    Pattern.compile("(?i)近况|战绩|form|recent\\s*matches"),
                    Pattern.compile("(?i)比较|对比|论战|跨联赛|两队|两支球队|compare|comparison")
            )),
            new IntentRule("news-analysis", List.of(
                    Pattern.compile("(?i)新闻|资讯|news|article"),
                    Pattern.compile("(?i)报道|热点|headline")
            )),
            new IntentRule("prediction", List.of(
                    Pattern.compile("(?i)预测|胜率|概率|predict|prob| odds"),
                    Pattern.compile("(?i)谁会赢|能不能赢|结果|result|outcome")
            )),
            new IntentRule("small-talk", List.of(
                    Pattern.compile("(?i)你好|hi|hello|hey|早上好|晚上好|谢谢|bye")
            ))
    );

    public Map<String, Object> classify(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        if (text.isBlank()) {
            result.put("intent", "general");
            result.put("confidence", 0.0);
            return result;
        }
        for (IntentRule rule : rules) {
            if (rule.matches(text)) {
                result.put("intent", rule.intent());
                result.put("confidence", 0.7);
                return result;
            }
        }
        result.put("intent", "general");
        result.put("confidence", 0.3);
        return result;
    }
}
