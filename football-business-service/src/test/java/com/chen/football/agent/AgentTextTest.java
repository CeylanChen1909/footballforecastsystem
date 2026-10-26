package com.chen.football.agent;

import com.chen.football.agent.service.AgentText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTextTest {

    @Test
    void fixedAgentCopyFollowsExplicitLanguage() {
        assertEquals("Open match", AgentText.translate("en", "打开比赛"));
        assertEquals("打开比赛", AgentText.translate("zh", "打开比赛"));
        assertEquals("New session", AgentText.defaultSessionTitle("en"));
        assertEquals("新会话", AgentText.defaultSessionTitle("zh"));
        assertEquals("Read the server's unified prediction",
                AgentText.translate("en", "读取服务端统一预测结果"));
    }

    @Test
    void providerStatusKeepsCanonicalEnumAndAddsEnglishLabel() {
        assertEquals("Normal", AgentText.statusLabel("en", "正常"));
        assertEquals("Not verified", AgentText.statusLabel("en", "未验证"));
        assertEquals("正常", AgentText.statusLabel("zh", "正常"));
        assertEquals("AVAILABLE", AgentText.statusLabel("zh", "AVAILABLE"));
    }

    @Test
    void sessionTitleOnlyLocalizesDefaultTitles() {
        assertEquals("New session", AgentText.sessionTitle("en", "新会话"));
        assertEquals("新会话", AgentText.sessionTitle("zh", "New session"));
        assertEquals("My own title", AgentText.sessionTitle("en", "My own title"));
    }
}
