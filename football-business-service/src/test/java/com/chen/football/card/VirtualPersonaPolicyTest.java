package com.chen.football.card;

import com.chen.football.card.service.VirtualPersonaPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualPersonaPolicyTest {
    private final VirtualPersonaPolicy policy = new VirtualPersonaPolicy();

    @Test
    void acceptsFictionalAcgnAndMemeEntries() {
        assertTrue(policy.accepts("孙悟空", "虚构人物，出自漫画和动画", "一个经典角色", "standard"));
        assertTrue(policy.accepts("Doge", "Internet meme", "网络迷因角色", "standard"));
        // Some zh-Wikipedia character pages have no description field and
        // identify themselves only in the extract (e.g. 神樂 (銀魂)).
        assertTrue(policy.accepts("神樂 (銀魂)", "", "神樂是日本漫畫作品《銀魂》的虛構角色。", "standard"));
    }

    @Test
    void rejectsRealPeopleAndDisambiguationPages() {
        assertFalse(policy.accepts("Marie Curie", "科学家", "法国科学家", "standard"));
        assertFalse(policy.accepts("某位作家", "writer", "author of a fictional character", "standard"));
        assertFalse(policy.accepts("Mario", "fictional character", "多个条目", "disambiguation"));
        assertFalse(policy.accepts("普通条目", "城市", "一个城市", "standard"));
        assertFalse(policy.accepts("火影忍者", "日本漫画系列", "作品中的角色包括漩涡鸣人", "standard"));
        assertFalse(policy.accepts("神樂鉢", "日本系列漫畫", "作品中的角色是虚构角色", "standard"));
        assertFalse(policy.accepts("火影忍者剧场版", "动画电影", "一部电影作品", "standard"));
        assertFalse(policy.accepts("火影忍者角色列表", "角色列表", "列出作品角色", "standard"));
    }

    @Test
    void blocksUnsafeInputAndUnsafeExtractEvenWhenCharacterMetadataLooksValid() {
        assertTrue(policy.isBlockedInput("P.O.R.N"));
        assertTrue(policy.isBlockedInput("未成年"));
        assertFalse(policy.accepts("某虚构角色", "fictional character", "内容包含 under-age sexual abuse 语义", "standard"));
        assertTrue(policy.containsUnsafeContent("这是一个色情或性侵相关条目"));
        assertFalse(policy.containsUnsafeContent("虚构角色，冒险家"));
    }
}
