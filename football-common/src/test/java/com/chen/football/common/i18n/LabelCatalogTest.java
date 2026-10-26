package com.chen.football.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelCatalogTest {

    @AfterEach
    void clear() {
        LangContext.clear();
    }

    @Test
    void normalizesChineseEnglishAndProviderLeagueValuesToTheInternalKey() {
        assertEquals("英超", LabelCatalog.canonicalLeague("英超"));
        assertEquals("英超", LabelCatalog.canonicalLeague("Premier League"));
        assertEquals("英超", LabelCatalog.canonicalLeague("premier league"));
        assertEquals("英超", LabelCatalog.canonicalLeague("EPL"));
        assertEquals("英超", LabelCatalog.canonicalLeague("39"));
        assertEquals("英超", LabelCatalog.canonicalLeague("bbc-premier-league"));
        assertEquals("欧冠", LabelCatalog.canonicalLeague("UEFA Champions League"));
        assertEquals("欧冠", LabelCatalog.canonicalLeague("欧冠"));
        assertEquals("欧国联", LabelCatalog.canonicalLeague("Nations League"));
    }

    @Test
    void coversEveryLeagueFromTheNormalizerAndProductionScope() {
        List<String> competitions = List.of(
                "英超", "英冠", "英甲", "英乙", "西甲", "西乙", "意甲", "意乙", "德甲", "德乙",
                "法甲", "法乙", "葡超", "葡甲", "荷甲", "比甲", "苏超", "土超",
                "欧冠", "欧联", "欧协联", "欧国联",
                "世界杯", "欧洲杯", "世俱杯", "巴西甲", "巴西乙", "美职联", "墨超", "阿甲",
                "解放者杯", "南美杯", "中超", "日职联", "韩K联", "意大利杯", "英锦赛", "足总杯",
                "英联杯", "国际友谊赛", "捷克甲", "乌超");
        for (String competition : competitions) {
            assertTrue(LabelCatalog.isKnownLeague(competition), competition);
            String english = LabelCatalog.leagueEnglish(competition);
            assertTrue(english != null && !english.isBlank(), competition);
            assertTrue(!I18nText.containsChinese(english), competition + " -> " + english);
            assertEquals(competition, LabelCatalog.canonicalLeague(english), competition);
        }
    }

    @Test
    void leagueDisplayFollowsTheRequestedLanguage() {
        LangContext.set("en");
        assertEquals("Premier League", LabelCatalog.leagueDisplay("英超"));
        assertEquals("Premier League", LabelCatalog.leagueDisplay("EPL"));
        assertEquals("UEFA Champions League", LabelCatalog.leagueDisplay("欧冠"));
        LangContext.set("zh");
        assertEquals("英超", LabelCatalog.leagueDisplay("Premier League"));
        assertEquals("欧冠", LabelCatalog.leagueDisplay("UEFA Champions League"));
    }

    @Test
    void teamDisplayUsesTheEnglishCanonicalNameInEnglishMode() {
        LangContext.set("en");
        assertEquals("Manchester City", LabelCatalog.teamDisplay("曼城"));
        assertEquals("Arsenal", LabelCatalog.teamDisplay("阿森纳"));
        assertEquals("Paris Saint-Germain", LabelCatalog.teamDisplay("大巴黎"));
        assertEquals("Bayern Munich", LabelCatalog.teamDisplay("拜仁"));
        assertEquals("Bayer 04 Leverkusen", LabelCatalog.teamDisplay("勒沃库森"));
        LangContext.set("zh");
        assertEquals("曼城", LabelCatalog.teamDisplay("Manchester City"));
        assertEquals("阿森纳", LabelCatalog.teamDisplay("Arsenal"));
        assertEquals("巴黎圣日耳曼", LabelCatalog.teamDisplay("PSG"));
    }

    @Test
    void teamVariantsResolveChineseEnglishAndAliasesToTheSameCandidateSet() {
        List<String> chinese = LabelCatalog.teamNameVariants("利物浦");
        List<String> english = LabelCatalog.teamNameVariants("Liverpool");
        assertEquals(english, chinese);
        assertTrue(english.contains("Liverpool"));
        assertTrue(english.contains("利物浦"));

        List<String> villarrealZh = LabelCatalog.teamNameVariants("比利亚雷亚尔");
        List<String> villarrealEn = LabelCatalog.teamNameVariants("Villarreal");
        assertEquals(villarrealEn, villarrealZh);
        assertTrue(villarrealEn.contains("Villarreal"));
        assertTrue(villarrealEn.contains("比利亚雷亚尔"));

        assertEquals(LabelCatalog.teamNameVariants("大巴黎"), LabelCatalog.teamNameVariants("PSG"));
        assertEquals(LabelCatalog.teamNameVariants("枪手"), LabelCatalog.teamNameVariants("Arsenal"));
    }

    @Test
    void unknownValuesAreReturnedUnchanged() {
        assertEquals("Made Up League", LabelCatalog.canonicalLeague("Made Up League"));
        assertEquals("Made Up FC", LabelCatalog.teamDisplay("Made Up FC"));
        assertEquals("Utility Player", LabelCatalog.positionDisplay("Utility Player"));
    }

    @Test
    void positionsAndZoneLabelsHaveEnglishNames() {
        LangContext.set("en");
        assertEquals("Goalkeeper", LabelCatalog.positionDisplay("门将"));
        assertEquals("Defender", LabelCatalog.positionDisplay("后卫"));
        assertEquals("Midfielder", LabelCatalog.positionDisplay("中场"));
        assertEquals("Forward", LabelCatalog.positionDisplay("前锋"));
        assertEquals("Wide Midfielder", LabelCatalog.positionDisplay("边前卫"));
        assertEquals("Versatile", LabelCatalog.positionDisplay("全能"));
        assertEquals("Left-Back", LabelCatalog.positionDisplay("左后卫"));
        assertEquals("UEFA Champions League", LabelCatalog.zoneDisplay("欧冠正赛"));
        assertEquals("Relegation", LabelCatalog.zoneDisplay("降级"));
        LangContext.set("zh");
        assertEquals("门将", LabelCatalog.positionDisplay("Goalkeeper"));
        assertEquals("欧冠正赛", LabelCatalog.zoneDisplay("UEFA Champions League"));
    }

    @Test
    void positionAliasesKeepEnglishStable() {
        LangContext.set("en");
        assertEquals("Goalkeeper", LabelCatalog.positionEnglish("GK"));
        assertEquals("Goalkeeper", LabelCatalog.positionEnglish("门将"));
        assertEquals("Centre-Back", LabelCatalog.positionEnglish("中后卫"));
        LangContext.set("zh");
        assertEquals("门将", LabelCatalog.positionChinese("GK"));
        assertEquals("门将", LabelCatalog.positionChinese("Goalkeeper"));
    }
}
