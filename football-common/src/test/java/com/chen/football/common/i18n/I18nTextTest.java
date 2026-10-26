package com.chen.football.common.i18n;

import com.chen.football.common.dto.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nTextTest {

    @AfterEach
    void clear() {
        LangContext.clear();
    }

    @Test
    void defaultsToEnglishAndTranslatesStaticMessages() {
        assertEquals("Success", I18nText.translate("获取成功"));
        assertEquals("Request failed: timeout", I18nText.translate("获取失败: timeout"));
        assertEquals("No standings available", I18nText.translate("暂无积分榜"));
        assertEquals("System error", ErrorCode.SYSTEM_ERROR.defaultMessage());
    }

    @Test
    void handlesDynamicMessages() {
        assertEquals("Returned 12 matches", I18nText.translate("已返回 12 场比赛"));
        assertEquals("Returned 5 teams across 2 groups", I18nText.translate("已按 2 个小组返回 5 支球队"));
        assertEquals("Returned 3 teams; no points recorded yet this season",
                I18nText.translate("已返回 3 支球队，当前赛季尚未产生可验证积分"));
        assertEquals("Some features are missing: xg", I18nText.translate("部分特征缺失：xg"));
        assertEquals("Team 42", I18nText.translate("球队42"));
    }

    @Test
    void chineseModeKeepsOriginalText() {
        LangContext.set("zh");
        assertEquals("获取成功", I18nText.translate("获取成功"));
        assertEquals("暂无积分榜", I18nText.translate("暂无积分榜"));
        assertEquals("系统错误", ErrorCode.SYSTEM_ERROR.defaultMessage());
    }

    @Test
    void unknownOrDatabaseAuthoredChineseIsNotRewritten() {
        assertEquals("一篇后台录入的中文新闻标题", I18nText.translate("一篇后台录入的中文新闻标题"));
        assertEquals("Already English", I18nText.translate("Already English"));
        assertEquals(null, I18nText.translate(null));
    }

    @Test
    void ofChoosesTheRequestedVariant() {
        assertEquals("Live now", I18nText.of("正在进行", "Live now"));
        LangContext.set("zh");
        assertEquals("正在进行", I18nText.of("正在进行", "Live now"));
    }
}
