package com.chen.football.gateway.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayLangResolverTest {

    @Test
    void readsOnlyTheLangValueFromTheRawQuery() {
        assertNull(GatewayLangResolver.queryLang(null));
        assertNull(GatewayLangResolver.queryLang("leagueName=Premier%20League&homeTeam=Liverpool"));
        assertEquals("zh", GatewayLangResolver.queryLang("leagueName=Premier%20League&lang=zh"));
        assertEquals("en-US", GatewayLangResolver.queryLang("lang=en-US&homeTeam=Liverpool"));
    }

    @Test
    void malformedPercentEscapesDoNotBreakResolution() {
        // The malformed lang value is ignored; the gateway defaults to English.
        assertEquals("en", GatewayLangResolver.resolve(
                GatewayLangResolver.queryLang("leagueName=Premier%20League&lang=%ZZ"), null, null));
        assertEquals("zh", GatewayLangResolver.resolve(
                GatewayLangResolver.queryLang("lang=zh&homeTeam=%ZZ"), null, null));
    }
}
