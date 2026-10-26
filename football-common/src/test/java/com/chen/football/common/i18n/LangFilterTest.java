package com.chen.football.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LangFilterTest {

    private final LangFilter filter = new LangFilter();

    @AfterEach
    void clear() {
        LangContext.clear();
    }

    @Test
    void setsLanguageForTheRequestAndClearsItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/crawler/matches/today");
        request.setParameter("lang", "zh");
        request.addHeader("X-Lang", "en");
        request.addHeader("Accept-Language", "en-US");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(LangContext.get()));

        assertEquals("zh", seen.get());
        // OncePerRequestFilter removes the thread-local before returning.
        assertEquals("en", LangContext.get());
    }

    @Test
    void defaultsToEnglishForPlainRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/crawler/matches/today");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(LangContext.get()));

        assertEquals("en", seen.get());
    }

    @Test
    void malformedQueryEncodingDoesNotPreventTheRequestFromReachingTheController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/proxy/prediction");
        request.setQueryString("leagueName=Premier%20League&homeTeam=Liverpool&lang=%ZZ");
        AtomicReference<String> seen = new AtomicReference<>();

        assertDoesNotThrow(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(LangContext.get())));

        // An unusable lang value falls back to the default; the unrelated
        // malformed parameter no longer aborts the filter chain.
        assertEquals("en", seen.get());
    }

    @Test
    void readsOnlyTheLangParameterFromTheRawQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/proxy/prediction");
        request.setQueryString("leagueName=Premier%20League&lang=zh&homeTeam=Liverpool");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(LangContext.get()));

        assertEquals("zh", seen.get());
    }

    @Test
    void delegatesToTheNextFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("/api/users/me", request.getRequestURI());
    }
}
