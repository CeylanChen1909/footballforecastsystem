package com.chen.football.common.i18n;

/**
 * Request-scoped language holder for servlet requests.
 *
 * <p>The product default is English.  The value is set by {@link LangFilter}
 * from the {@code lang} query parameter, the {@code X-Lang} request header or
 * {@code Accept-Language}, in that order.</p>
 */
public final class LangContext {

    public static final String EN = "en";
    public static final String ZH = "zh";
    public static final String DEFAULT = EN;

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private LangContext() {
    }

    public static void set(String lang) {
        CURRENT.set(normalize(lang));
    }

    /** Returns the active language, falling back to {@link #DEFAULT}. */
    public static String get() {
        String value = CURRENT.get();
        return value == null || value.isBlank() ? DEFAULT : value;
    }

    public static boolean isEnglish() {
        return EN.equals(get());
    }

    public static boolean isChinese() {
        return ZH.equals(get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Maps arbitrary language tags such as {@code zh-CN} / {@code en-US} to en|zh. */
    public static String normalize(String lang) {
        if (lang == null) return DEFAULT;
        String value = lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith(ZH)) return ZH;
        if (value.startsWith(EN)) return EN;
        return DEFAULT;
    }
}
