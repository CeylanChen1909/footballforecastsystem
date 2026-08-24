package com.chen.football.card.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Explicit allow-list policy for virtual-character cards. */
@Component
public class VirtualPersonaPolicy {
    private static final List<String> CHARACTER_MARKERS = List.of(
            "虚构", "角色", "人物角色", "主角", "主人公", "网络迷因", "迷因", "吉祥物", "二次元",
            "fictional", "character", "protagonist", "internet meme", "meme", "mascot");
    private static final List<String> NON_CHARACTER_MARKERS = List.of(
            "列表", "系列", "剧场版", "电影", "作品", "角色列表", "国家", "地点", "组织", "道具", "武器",
            "list", "series", "film", "movie", "franchise", "episode", "location", "organization", "weapon");
    private static final List<String> REAL_PERSON_MARKERS = List.of(
            "真人", "政治人物", "足球运动员", "运动员", "演员", "歌手", "作家", "企业家", "科学家", "president",
            "politician", "footballer", "athlete", "actor", "singer", "writer", "scientist", "entrepreneur");
    private static final List<String> UNSAFE_CONTENT_MARKERS = List.of(
            "色情", "情色", "淫秽", "性侵", "强奸", "儿童色情", "恋童", "未成年", "未满18", "裸露",
            "porn", "erotic", "sexuallyexplicit", "sexualabuse", "rape", "pedophil", "underage", "incest",
            "terrorist", "terrorism", "extremist", "nazi", "genocide");

    public boolean isBlockedInput(String value) {
        String text = compact(value);
        return UNSAFE_CONTENT_MARKERS.stream().anyMatch(text::contains)
                || List.of("政治", "总统", "总理", "politic", "president", "primeminister").stream().anyMatch(text::contains);
    }

    public boolean containsUnsafeContent(String value) {
        return UNSAFE_CONTENT_MARKERS.stream().anyMatch(compact(value)::contains);
    }

    public boolean accepts(String title, String description, String extract, String type) {
        if ("disambiguation".equalsIgnoreCase(normalize(type))) return false;
        String typeText = normalize(title) + " " + normalize(description);
        String normalizedExtract = normalize(extract);
        String safetyText = compact(title + " " + description + " " + extract);
        // Require explicit character semantics in the title/description. A
        // work page can mention fictional characters in its extract, but it
        // is not itself a character card candidate.
        boolean virtual = CHARACTER_MARKERS.stream().anyMatch(typeText::contains);
        // Character pages in Wikipedia often have no description field. For
        // example, "神樂 (銀魂)" is described only in the extract as “the
        // fictional character ...”. Treat that explicit sentence as a strong
        // signal, while still requiring the title/description not to look like
        // a work, list, location or organization.
        boolean explicitFictionalCharacter = normalizedExtract.contains("虚构角色")
                || normalizedExtract.contains("虛構角色")
                || normalizedExtract.contains("fictional character")
                || normalizedExtract.contains("fictional protagonist")
                || normalizedExtract.contains("fictional person");
        if (explicitFictionalCharacter) virtual = true;
        boolean nonCharacter = NON_CHARACTER_MARKERS.stream().anyMatch(typeText::contains);
        // Keep the real-person check focused on the title/description.  A
        // fictional character summary can legitimately mention actors,
        // athletes or a live-action adaptation in its extract.
        boolean realPerson = REAL_PERSON_MARKERS.stream().anyMatch(typeText::contains);
        boolean unsafe = UNSAFE_CONTENT_MARKERS.stream().anyMatch(safetyText::contains);
        return virtual && !nonCharacter && !realPerson && !unsafe;
    }

    private String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private String compact(String value) {
        return normalize(value).replaceAll("[\\p{Z}\\p{P}\\p{S}\\p{Cf}]", "");
    }
}
