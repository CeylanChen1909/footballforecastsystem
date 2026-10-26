package com.chen.football.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangResolverTest {

    @AfterEach
    void clear() {
        LangContext.clear();
    }

    @Test
    void defaultsToEnglishWhenNothingIsProvided() {
        assertEquals("en", LangResolver.resolve(null, null, null));
        assertEquals("en", LangResolver.resolve("", "", ""));
        assertEquals("en", LangResolver.resolve("fr", "de", "it-IT"));
    }

    @Test
    void queryParameterWinsOverHeaderAndAcceptLanguage() {
        assertEquals("zh", LangResolver.resolve("zh", "en", "en-US,en;q=0.9"));
        assertEquals("en", LangResolver.resolve("en", "zh", "zh-CN,zh;q=0.9"));
    }

    @Test
    void xLangIsUsedWhenQueryIsMissingOrInvalid() {
        assertEquals("zh", LangResolver.resolve(null, "zh-TW", "en-US"));
        assertEquals("en", LangResolver.resolve("fr", "en-GB", "zh-CN"));
    }

    @Test
    void acceptLanguageUsesQualityAndRegionalTags() {
        assertEquals("zh", LangResolver.resolve(null, null, "zh-CN,zh;q=0.9,en;q=0.8"));
        assertEquals("en", LangResolver.resolve(null, null, "fr-FR,zh;q=0.4,en;q=0.9"));
        assertEquals("zh", LangResolver.resolve(null, null, "en;q=0.5,zh;q=0.8"));
        assertEquals("en", LangResolver.resolve(null, null, "zh;q=0,en;q=0.7"));
    }

    @Test
    void contextNormalizesAndFallsBackToEnglish() {
        LangContext.set("zh-CN");
        assertTrue(LangContext.isChinese());
        assertEquals("zh", LangContext.get());
        LangContext.set(null);
        assertEquals("en", LangContext.get());
        assertTrue(LangContext.isEnglish());
        assertFalse(LangContext.isChinese());
    }
}
