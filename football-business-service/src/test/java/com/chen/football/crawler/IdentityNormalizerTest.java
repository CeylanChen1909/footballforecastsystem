package com.chen.football.crawler;

import com.chen.football.crawler.service.IdentityNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

class IdentityNormalizerTest {
    @Test
    void matchesAccentsAndProviderAliases() {
        assertEquals("alaves", IdentityNormalizer.normalize("Alavés"));
        assertEquals("alaves", IdentityNormalizer.normalize("Alaves"));
        assertEquals("lens", IdentityNormalizer.normalize("Racing Club de Lens"));
        assertEquals("lens", IdentityNormalizer.normalize("Lens"));
        assertEquals("inter", IdentityNormalizer.normalize("Inter Milan"));
        assertEquals("inter", IdentityNormalizer.normalize("FC Internazionale Milano"));
        assertEquals("nacional", IdentityNormalizer.normalize("CD Nacional"));
        assertEquals("nacional", IdentityNormalizer.normalize("Nacional"));
        assertTrue(IdentityNormalizer.compatible("Nottingham", "Nottingham Forest"));
        assertTrue(IdentityNormalizer.compatible("Brighton", "Brighton & Hove Albion"));
    }

    @Test
    void matchKeyKeepsKickoffSlotToAvoidSameDayMerges() {
        String early = IdentityNormalizer.matchKey("PL", "Arsenal", "Chelsea", LocalDateTime.of(2026, 8, 22, 15, 0));
        String late = IdentityNormalizer.matchKey("PL", "Arsenal", "Chelsea", LocalDateTime.of(2026, 8, 22, 19, 0));
        assertTrue(!early.equals(late));
    }
}
