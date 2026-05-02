package com.qingmei.reviewplatform.service;

import com.qingmei.reviewplatform.config.AppProperties;
import com.qingmei.reviewplatform.model.UserAccount;
import com.qingmei.reviewplatform.repository.NotFoundException;
import com.qingmei.reviewplatform.repository.ReviewRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final String SESSION_KEY_PREFIX = "auth:session:";

    private final ReviewRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public AuthService(ReviewRepository repository,
                       StringRedisTemplate redisTemplate,
                       PasswordEncoder passwordEncoder,
                       AppProperties appProperties) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    public record AuthUser(String id, String username, OffsetDateTime createdAt, boolean admin) {
    }

    public record AuthResult(String token, AuthUser user) {
    }

    public AuthResult register(String username, String password) {
        String normalized = normalizeUsername(username);
        validatePassword(password);

        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            repository.createUser(user);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("用户名已存在");
        }

        String token = createSession(user.getUsername());
        return new AuthResult(token, toAuthUser(user));
    }

    public AuthResult login(String username, String password) {
        String normalized = normalizeUsername(username);
        validatePassword(password);

        UserAccount user;
        try {
            user = repository.getUserByUsername(normalized);
        } catch (NotFoundException ex) {
            throw new UnauthorizedException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new UnauthorizedException("用户名或密码错误");
        }

        String token = createSession(user.getUsername());
        return new AuthResult(token, toAuthUser(user));
    }

    public AuthUser me(String token) {
        String username = resolveUsername(token);
        if (username == null || username.isBlank()) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        try {
            UserAccount user = repository.getUserByUsername(username);
            return toAuthUser(user);
        } catch (NotFoundException ex) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        redisTemplate.delete(SESSION_KEY_PREFIX + token);
    }

    public String resolveUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + token);
    }

    public boolean isAdmin(String username) {
        return "admin".equals(normalizeUsername(username));
    }

    private String createSession(String username) {
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + token, username, appProperties.getAuthSessionTtl());
        return token;
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > 32 || !normalized.matches("[a-z0-9._-]+")) {
            throw new BadRequestException("用户名需要 3-32 位，仅允许字母、数字、点、下划线和短横线");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        String raw = password == null ? "" : password;
        if (raw.length() < 6 || raw.length() > 64) {
            throw new BadRequestException("密码长度需要在 6 到 64 位之间");
        }
    }

    private AuthUser toAuthUser(UserAccount user) {
        return new AuthUser(user.getId(), user.getUsername(), user.getCreatedAt(), isAdmin(user.getUsername()));
    }
}
