package com.chen.football.agent.tool;

import java.util.Map;

public interface AgentTool {
    String name();
    Map<String, Object> execute(Map<String, Object> context);
}
