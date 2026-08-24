package com.chen.football.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentResultParser {

    private final ObjectMapper objectMapper;

    public AgentResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public AgentStructuredResult parse(Map<String, Object> aiResult) {
        if (aiResult == null) {
            return AgentStructuredResult.fallback("模型返回为空", null);
        }
        Object content = aiResult.get("content");
        if (content == null || String.valueOf(content).isBlank()) {
            Object summary = aiResult.get("summary");
            String text = summary == null ? "模型未返回可解析内容" : String.valueOf(summary);
            return AgentStructuredResult.fallback(text, text);
        }
        String text = String.valueOf(content).trim();
        String json = AgentJsonTools.extractJson(text);
        if (json == null || !AgentJsonTools.looksLikeJson(json)) {
            return AgentStructuredResult.fallback(text, text);
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String summary = string(parsed.get("summary"), text);
            double confidence = Math.max(0, Math.min(1, toDouble(parsed.get("confidence"), 0.3)));
            List<String> keyPoints = boundedList(parsed.get("keyPoints"), 8, 500);
            List<String> risks = boundedList(parsed.get("risks"), 8, 500);
            String recommendation = boundedText(string(parsed.get("recommendation"), ""), 800);
            List<String> followUp = boundedList(parsed.get("followUpQuestions"), 5, 300);
            return new AgentStructuredResult(summary, confidence, keyPoints, risks, recommendation, followUp, true, text);
        } catch (JsonProcessingException e) {
            return AgentStructuredResult.fallback(text, text);
        }
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private double toDouble(Object value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    private List<String> boundedList(Object value, int maxItems, int maxChars) {
        List<String> raw = toStringList(value);
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            if (result.size() >= maxItems) break;
            String bounded = boundedText(item, maxChars);
            if (!bounded.isBlank()) result.add(bounded);
        }
        return result;
    }

    private String boundedText(String value, int maxChars) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
