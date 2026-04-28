package com.qingmei.reviewplatform.web;

import com.qingmei.reviewplatform.service.AuthService;
import com.qingmei.reviewplatform.service.BadRequestException;
import com.qingmei.reviewplatform.service.ConflictException;
import com.qingmei.reviewplatform.service.UnauthorizedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) AuthRequest req) {
        if (req == null) {
            return error(HttpStatus.BAD_REQUEST, "请求参数不能为空");
        }
        try {
            AuthService.AuthResult result = authService.register(req.username(), req.password());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "token", result.token(),
                    "user", result.user()
            ));
        } catch (BadRequestException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (ConflictException ex) {
            return error(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) AuthRequest req) {
        if (req == null) {
            return error(HttpStatus.BAD_REQUEST, "请求参数不能为空");
        }
        try {
            AuthService.AuthResult result = authService.login(req.username(), req.password());
            return ResponseEntity.ok(Map.of(
                    "token", result.token(),
                    "user", result.user()
            ));
        } catch (BadRequestException ex) {
            return error(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (UnauthorizedException ex) {
            return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    @GetMapping("/api/v1/auth/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        try {
            return ResponseEntity.ok(Map.of("user", authService.me(bearerToken(authorization))));
        } catch (UnauthorizedException ex) {
            return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(bearerToken(authorization));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "";
        }
        return authorization.substring(7).trim();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
