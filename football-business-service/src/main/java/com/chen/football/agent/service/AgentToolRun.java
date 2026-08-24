package com.chen.football.agent.service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * The normalized result of one Agent tool-planning pass.  Keeping this
 * object separate from the model response lets the synchronous chat API and
 * the SSE API expose the same tool execution semantics.
 */
public record AgentToolRun(
        String intent,
        double intentConfidence,
        Map<String, Object> context,
        Map<String, Object> toolOutputs,
        List<String> steps,
        List<String> skippedTools,
        Map<String, Long> toolLatencies,
        List<Map<String, Object>> evidence
) {
    public AgentToolRun {
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        toolOutputs = toolOutputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(toolOutputs));
        steps = steps == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(steps));
        skippedTools = skippedTools == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(skippedTools));
        toolLatencies = toolLatencies == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(toolLatencies));
        evidence = evidence == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(evidence));
    }
}
