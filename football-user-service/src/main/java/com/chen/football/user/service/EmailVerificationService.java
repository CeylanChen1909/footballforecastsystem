package com.chen.football.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/** 邮箱验证码发送与一次性校验。未配置 SMTP 时仅允许控制台开发模式，不向客户端回显验证码。 */
@Service
public class EmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitService rateLimitService;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Map<String, CodeEntry> localCodes = new ConcurrentHashMap<>();

    @Value("${security.email-verification.enabled:true}")
    private boolean enabled;
    @Value("${security.email-verification.console-mode:false}")
    private boolean consoleMode;
    @Value("${spring.mail.username:}")
    private String mailFrom;

    public EmailVerificationService(StringRedisTemplate redisTemplate,
                                    RateLimitService rateLimitService,
                                    ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.redisTemplate = redisTemplate;
        this.rateLimitService = rateLimitService;
        this.mailSenderProvider = mailSenderProvider;
    }

    public Map<String, Object> sendCode(String rawEmail, String scene, String ip) {
        String email = normalizeEmail(rawEmail);
        String normalizedScene = normalizeScene(scene);
        if (!isValidEmail(email)) return fail("请输入有效邮箱");
        if (!enabled) return fail("邮箱验证功能尚未启用");
        if (!rateLimitService.isAllowed("email-code:" + normalizedScene + ":" + email, 1, 60)) {
            return fail("验证码发送过于频繁，请 60 秒后再试");
        }
        if (!rateLimitService.isAllowed("email-code-ip:" + ip, 10, 3600)) {
            return fail("验证码请求过于频繁，请稍后再试");
        }
        if (!rateLimitService.isAllowed("email-code-daily:" + email, 5, 86_400)) {
            return fail("该邮箱今日验证码请求已达上限，请明天再试");
        }
        if (!rateLimitService.isAllowed("email-code-ip-daily:" + ip, 30, 86_400)) {
            return fail("当前网络今日验证码请求已达上限，请稍后再试");
        }
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String codeKey = key(normalizedScene, email);
        try {
            redisTemplate.opsForValue().set(codeKey, code, CODE_TTL);
        } catch (RuntimeException unavailable) {
            localCodes.put(codeKey, new CodeEntry(code, Instant.now().plus(CODE_TTL)));
            log.warn("Redis 不可用，邮箱验证码暂使用进程内短期存储");
        }
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        boolean delivered = false;
        if (sender != null && mailFrom != null && !mailFrom.isBlank()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(email);
                message.setSubject("ChenFootball 邮箱验证码");
                message.setText("你的验证码是 " + code + "，10 分钟内有效。如非本人操作请忽略此邮件。");
                sender.send(message);
                delivered = true;
            } catch (Exception ex) {
                log.warn("邮箱验证码发送失败，email={}, error={}", mask(email), ex.getMessage());
            }
        }
        if (!delivered) {
            if (!consoleMode) {
                try { redisTemplate.delete(codeKey); } catch (RuntimeException ignored) { }
                localCodes.remove(codeKey);
                return fail("邮件服务暂不可用，请稍后再试");
            }
            log.warn("[EmailVerification][console-mode] scene={}, email={}, code={}", normalizedScene, mask(email), code);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("delivery", delivered ? "smtp" : "console");
        result.put("expiresInSeconds", CODE_TTL.toSeconds());
        return result;
    }

    public boolean verifyAndConsume(String rawEmail, String scene, String rawCode) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!isValidEmail(email) || !code.matches("\\d{6}")) return false;
        // 验证码即使只有六位，也必须有失败次数上限，避免在线穷举。
        if (!rateLimitService.isAllowed("email-code-verify:" + normalizeScene(scene) + ":" + email, 5, 600)) {
            return false;
        }
        String codeKey = key(normalizeScene(scene), email);
        String stored;
        try {
            stored = redisTemplate.opsForValue().get(codeKey);
        } catch (RuntimeException unavailable) {
            CodeEntry entry = localCodes.remove(codeKey);
            stored = entry != null && !entry.expiresAt().isBefore(Instant.now()) ? entry.code() : null;
        }
        if (stored == null) return false;
        boolean matched = MessageDigest.isEqual(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8), code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (matched) {
            try { redisTemplate.delete(codeKey); } catch (RuntimeException ignored) { }
            localCodes.remove(codeKey);
        }
        return matched;
    }

    public static String normalizeEmail(String email) { return email == null ? "" : email.trim().toLowerCase(Locale.ROOT); }

    public static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && email.matches("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$");
    }

    private String key(String scene, String email) { return "security:email-code:" + scene + ":" + email; }
    private String normalizeScene(String scene) { return "RESET".equalsIgnoreCase(scene) ? "RESET" : "REGISTER"; }
    private String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return "***" + (at > 0 ? email.substring(at) : "");
        return email.substring(0, 2) + "***" + email.substring(at);
    }
    private Map<String, Object> fail(String message) { return Map.of("ok", false, "message", message); }
    private record CodeEntry(String code, Instant expiresAt) { }
}
