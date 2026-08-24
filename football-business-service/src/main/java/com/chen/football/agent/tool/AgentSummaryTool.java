package com.chen.football.agent.tool;

import com.chen.football.common.config.CrawlerProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentSummaryTool implements AgentTool {

    private final CrawlerProperties crawlerProperties;

    public AgentSummaryTool(CrawlerProperties crawlerProperties) {
        this.crawlerProperties = crawlerProperties;
    }

    @Override
    public String name() {
        return "agent_summary";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("crawlerEnabled", crawlerProperties.isEnabled());
        data.put("requestIntervalMs", crawlerProperties.getRequestIntervalMs());
        data.put("primarySource", crawlerProperties.getPrimarySource());
        data.put("primaryOnly", crawlerProperties.isPrimaryOnly());
        data.put("status", crawlerProperties.isEnabled() ? "AVAILABLE" : "DISABLED");
        return data;
    }
}
