package com.chen.football.crawler.controller;

import com.chen.football.agent.service.AgentRateLimitService;
import com.chen.football.crawler.service.DeepSeekPredictionService;
import com.chen.football.crawler.service.ExternalPredictionProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PredictionProxyControllerTest {

    @Test
    void retiredEndpointReturnsGoneBeforeParsingAnyParameter() throws Exception {
        ExternalPredictionProxyService proxyService = mock(ExternalPredictionProxyService.class);
        DeepSeekPredictionService deepSeek = mock(DeepSeekPredictionService.class);
        AgentRateLimitService rateLimit = mock(AgentRateLimitService.class);
        PredictionProxyController controller = new PredictionProxyController(proxyService, deepSeek, rateLimit);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String[] queries = {
                "",
                "?leagueName=Premier%20League&homeTeam=Liverpool",
                "?leagueName=%E8%8B%B1%E8%B6%85&homeTeam=%E5%88%A9%E7%89%A9%E6%B5%A6",
                "?fixtureId=not-a-number&leagueName=&homeTeam=&awayTeam=",
                "?fixtureId=1&homeTeam=Liverpool&awayTeam=Villarreal&leagueName=Premier%20League"
        };
        for (String query : queries) {
            mvc.perform(get("/api/proxy/prediction" + query))
                    .andExpect(status().isGone());
        }

        verifyNoInteractions(proxyService, deepSeek, rateLimit);
    }
}
