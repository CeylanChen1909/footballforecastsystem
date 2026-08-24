package com.chen.football.security;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.exception.BusinessException;
import com.chen.football.common.exception.UnauthorizedException;
import com.chen.football.common.util.AdminGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminGuardTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void rejectsAnonymousCrawlerOperations() {
        assertThrows(UnauthorizedException.class, () -> AdminGuard.requirePermission("CRAWLER"));
    }

    @Test
    void rejectsNormalUsersButAllowsAdminsForCrawlerOperations() {
        UserContext.set(7L, "user", "USER");
        assertThrows(BusinessException.class, () -> AdminGuard.requirePermission("CRAWLER"));

        UserContext.set(8L, "dev", "ADMIN");
        assertDoesNotThrow(() -> AdminGuard.requirePermission("CRAWLER"));
    }

    @Test
    void limitsUserAdministrationToSuperAdmin() {
        UserContext.set(8L, "admin", "ADMIN");
        assertThrows(BusinessException.class, () -> AdminGuard.requirePermission("USER"));

        UserContext.set(1L, "root", "SUPER_ADMIN");
        assertDoesNotThrow(() -> AdminGuard.requirePermission("USER"));
    }
}
