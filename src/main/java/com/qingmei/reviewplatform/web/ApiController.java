package com.qingmei.reviewplatform.web;

import com.qingmei.reviewplatform.model.Asset;
import com.qingmei.reviewplatform.model.ShareLink;
import com.qingmei.reviewplatform.repository.NotFoundException;
import com.qingmei.reviewplatform.service.AuthService;
import com.qingmei.reviewplatform.service.ExpiredShareException;
import com.qingmei.reviewplatform.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.util.UriUtils;

@RestController
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final ReviewService reviewService;
    private final AuthService authService;

    public ApiController(ReviewService reviewService, AuthService authService) {
        this.reviewService = reviewService;
        this.authService = authService;
    }

    @PostMapping("/api/v1/assets/upload")
    public ResponseEntity<Map<String, Object>> uploadAsset(HttpServletRequest request,
                                                           @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "missing file field");
        }

        try {
            String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
            String suffix = ext == null || ext.isBlank() ? "" : "." + ext;
            String storedName = System.currentTimeMillis() + "" + ThreadLocalRandom.current().nextInt(1000, 10000) + suffix;
            Path storagePath = reviewService.storageDir().resolve(storedName);
            file.transferTo(storagePath);

            String mimeType = file.getContentType();
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = URLConnection.guessContentTypeFromName(file.getOriginalFilename());
            }
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "application/octet-stream";
            }

            Asset asset = reviewService.createAsset(currentUsername(request), file.getOriginalFilename(), mimeType, file.getSize(), storagePath);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "asset", asset,
                    "preview_url", "/api/v1/assets/" + asset.getId() + "/stream",
                    "download_url", "/api/v1/assets/" + asset.getId() + "/download"
            ));
        } catch (IOException ex) {
            log.error("upload asset failed when saving file", ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "save file failed");
        } catch (Exception ex) {
            log.error("upload asset failed when creating db records", ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "create asset failed");
        }
    }

    @GetMapping("/api/v1/assets/{id}")
    public ResponseEntity<Map<String, Object>> getAsset(HttpServletRequest request, @PathVariable String id) {
        try {
            Asset asset = reviewService.getAsset(id, currentUsername(request));
            return ResponseEntity.ok(Map.of(
                    "asset", asset,
                    "preview_url", "/api/v1/assets/" + asset.getId() + "/stream",
                    "download_url", "/api/v1/assets/" + asset.getId() + "/download"
            ));
        } catch (NotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, "asset not found");
        } catch (Exception ex) {
            log.error("get asset failed, id={}", id, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "get asset failed");
        }
    }

    @GetMapping("/api/v1/assets")
    public ResponseEntity<Map<String, Object>> listAssets(HttpServletRequest request,
                                                          @RequestParam(defaultValue = "20") int limit,
                                                          @RequestParam(defaultValue = "0") int offset) {
        int fixedLimit = (limit <= 0 || limit > 100) ? 20 : limit;
        int fixedOffset = Math.max(0, offset);
        try {
            return ResponseEntity.ok(Map.of(
                    "items", reviewService.listAssets(currentUsername(request), fixedLimit, fixedOffset),
                    "limit", fixedLimit,
                    "offset", fixedOffset
            ));
        } catch (Exception ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "list assets failed");
        }
    }

    @GetMapping("/api/v1/assets/{id}/stream")
    public ResponseEntity<?> streamAsset(HttpServletRequest request, @PathVariable String id) {
        try {
            Asset asset = reviewService.getAsset(id, currentUsername(request));
            return fileResponse(asset, false);
        } catch (NotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, "asset not found");
        } catch (Exception ex) {
            log.error("stream asset failed, id={}", id, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "get asset failed");
        }
    }

    @GetMapping("/api/v1/assets/{id}/download")
    public ResponseEntity<?> downloadAsset(HttpServletRequest request, @PathVariable String id) {
        try {
            Asset asset = reviewService.getAsset(id, currentUsername(request));
            return fileResponse(asset, true);
        } catch (NotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, "asset not found");
        } catch (Exception ex) {
            log.error("download asset failed, id={}", id, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "get asset failed");
        }
    }

    @PostMapping("/api/v1/assets/{id}/share")
    public ResponseEntity<Map<String, Object>> createShare(HttpServletRequest request,
                                                            @PathVariable String id,
                                                            @RequestBody(required = false) ShareRequest req) {
        int expiryHours = req == null ? 0 : req.expiryHours();
        try {
            String annotationsJson = req == null ? "[]" : req.annotationsJson();
            ReviewService.ShareResult result = reviewService.createShare(currentUsername(request), id, expiryHours, annotationsJson);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "share", result.share(),
                    "share_link", result.fullLink()
            ));
        } catch (NotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, "asset not found");
        } catch (Exception ex) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "create share link failed");
        }
    }

    @GetMapping("/s/{token}")
    public ResponseEntity<?> openShare(@PathVariable String token) {
        try {
            Asset asset = reviewService.resolveShare(token);
            return fileResponse(asset, false);
        } catch (ExpiredShareException ex) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "share link expired"));
        } catch (NotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "share link not found"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "resolve share link failed"));
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    @DeleteMapping("/api/v1/assets/{id}")
    public ResponseEntity<Map<String, Object>> deleteAsset(HttpServletRequest request, @PathVariable String id) {
        try {
            reviewService.deleteAsset(currentUsername(request), id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NotFoundException ex) {
            return error(HttpStatus.NOT_FOUND, "asset not found");
        } catch (Exception ex) {
            log.error("delete asset failed, id={}", id, ex);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "delete asset failed");
        }
    }

    @GetMapping("/api/v1/public/shares/{token}")
    public ResponseEntity<Map<String, Object>> getShare(@PathVariable String token) {
        try {
            ShareLink share = reviewService.resolveShareLink(token);
            Asset asset = reviewService.resolveShare(token);
            return ResponseEntity.ok(Map.of(
                    "share", share,
                    "asset", asset,
                    "stream_url", "/s/" + share.getToken(),
                    "annotations_json", share.getAnnotationsJson() == null ? "[]" : share.getAnnotationsJson()
            ));
        } catch (ExpiredShareException ex) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "share link expired"));
        } catch (NotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "share link not found"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "resolve share link failed"));
        }
    }

    @PutMapping("/api/v1/shares/{token}/annotations")
    public ResponseEntity<Map<String, Object>> updateShareAnnotations(HttpServletRequest request,
                                                                       @PathVariable String token,
                                                                       @RequestBody(required = false) ShareAnnotationsRequest req) {
        currentUsername(request);
        try {
            ShareLink share = reviewService.updateShareAnnotations(token, req == null ? "[]" : req.annotationsJson());
            return ResponseEntity.ok(Map.of(
                    "share", share,
                    "annotations_json", share.getAnnotationsJson() == null ? "[]" : share.getAnnotationsJson()
            ));
        } catch (ExpiredShareException ex) {
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "share link expired"));
        } catch (NotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "share link not found"));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "update share annotations failed"));
        }
    }

    private String currentUsername(HttpServletRequest request) {
        Object attr = request.getAttribute("authUsername");
        if (attr instanceof String username && !username.isBlank()) {
            return username;
        }

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = "";
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).trim();
        }
        if (token.isBlank()) {
            token = request.getParameter("access_token");
        }

        String username = authService.resolveUsername(token);
        if (username == null || username.isBlank()) {
            throw new NotFoundException("user not found");
        }
        return username;
    }

    private ResponseEntity<Resource> fileResponse(Asset asset, boolean attachment) {
        FileSystemResource resource = new FileSystemResource(asset.getStoragePath());
        if (!resource.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(asset.getMimeType());
        } catch (InvalidMediaTypeException ex) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        if (!attachment) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(resource);
        }

        String fallbackName = asset.getStoredName().replaceAll("[^\\x20-\\x7E]", "_");
        String encodedName = UriUtils.encode(asset.getOriginalName(), StandardCharsets.UTF_8);
        String disposition = "attachment; filename=\"" + fallbackName + "\"; filename*=UTF-8''" + encodedName;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(mediaType)
                .body(resource);
    }

}
