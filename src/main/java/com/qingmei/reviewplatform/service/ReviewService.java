package com.qingmei.reviewplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingmei.reviewplatform.config.AppProperties;
import com.qingmei.reviewplatform.model.Asset;
import com.qingmei.reviewplatform.model.ReviewTask;
import com.qingmei.reviewplatform.model.ShareLink;
import com.qingmei.reviewplatform.repository.ReviewRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ReviewService {

    public static final String QUEUE_REVIEW_JOBS = "review:jobs";

    private final AppProperties appProperties;
    private final ReviewRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ReviewService(AppProperties appProperties, ReviewRepository repository, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Asset createAsset(String ownerUsername, String fileName, String mimeType, long size, Path storagePath) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID().toString());
        asset.setOwnerUsername(ownerUsername);
        asset.setOriginalName(fileName);
        asset.setStoredName(storagePath.getFileName().toString());
        asset.setMimeType(mimeType);
        asset.setSizeBytes(size);
        asset.setStoragePath(storagePath.toString());
        asset.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        repository.createAsset(asset);

        ReviewTask task = new ReviewTask();
        task.setId(UUID.randomUUID().toString());
        task.setAssetId(asset.getId());
        task.setStatus("queued");
        task.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        task.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        repository.createReviewTask(task);

        redisTemplate.opsForList().leftPush(QUEUE_REVIEW_JOBS, asset.getId());
        return asset;
    }

    public Asset getAsset(String id, String ownerUsername) {
        String cacheKey = "asset:" + ownerUsername + ":" + id;
        String raw = redisTemplate.opsForValue().get(cacheKey);
        if (raw != null) {
            try {
                Asset cached = objectMapper.readValue(raw, Asset.class);
                if (cached.getStoragePath() != null && !cached.getStoragePath().isBlank()) {
                    return cached;
                }
                redisTemplate.delete(cacheKey);
            } catch (IOException ignored) {
                redisTemplate.delete(cacheKey);
            }
        }

        Asset asset = repository.getAssetByIdAndOwner(id, ownerUsername);
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(asset), appProperties.getCacheTtl());
        } catch (JsonProcessingException ignored) {
        }
        return asset;
    }

    public List<Asset> listAssets(String ownerUsername, int limit, int offset) {
        return repository.listAssets(ownerUsername, limit, offset);
    }

    public void deleteAsset(String ownerUsername, String assetId) {
        Asset asset = repository.getAssetByIdAndOwner(assetId, ownerUsername);
        repository.deleteAssetByIdAndOwner(assetId, ownerUsername);
        redisTemplate.delete("asset:" + ownerUsername + ":" + assetId);
        try {
            Files.deleteIfExists(Path.of(asset.getStoragePath()));
        } catch (IOException ignored) {
        }
    }

    public record ShareResult(ShareLink share, String fullLink) {
    }

    public ShareResult createShare(String ownerUsername, String assetId, int expiryHours, String annotationsJson) {
        repository.getAssetByIdAndOwner(assetId, ownerUsername);

        ShareLink link = new ShareLink();
        link.setId(UUID.randomUUID().toString());
        link.setAssetId(assetId);
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setAnnotationsJson(safeAnnotationsJson(annotationsJson));
        link.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        if (expiryHours > 0) {
            link.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(expiryHours));
        } else {
            link.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plus(appProperties.getShareDefaultTtl()));
        }

        repository.createShareLink(link);
        String base = appProperties.getShareBaseUrl().replaceAll("/$", "");
        return new ShareResult(link, base + "/s/" + link.getToken());
    }

    public Asset resolveShare(String token) {
        ShareLink share = resolveShareLink(token);
        return repository.getAssetById(share.getAssetId());
    }

    public ShareLink resolveShareLink(String token) {
        ShareLink share = repository.getShareByToken(token);
        if (share.getExpiresAt() != null && share.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new ExpiredShareException("share link expired");
        }
        return share;
    }

    public ShareLink updateShareAnnotations(String token, String annotationsJson) {
        resolveShareLink(token);
        repository.updateShareAnnotations(token, safeAnnotationsJson(annotationsJson));
        return resolveShareLink(token);
    }

    private String safeAnnotationsJson(String annotationsJson) {
        if (annotationsJson == null || annotationsJson.isBlank()) {
            return "[]";
        }
        try {
            objectMapper.readTree(annotationsJson);
            return annotationsJson;
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    public void ensureStorageReady() {
        try {
            Files.createDirectories(Path.of(appProperties.getStorageDir()));
        } catch (IOException ex) {
            throw new IllegalStateException("init storage failed", ex);
        }
    }

    public Path storageDir() {
        return Path.of(appProperties.getStorageDir());
    }

    public void migrate() {
        repository.migrate();
    }

    public void setReviewTaskStatus(String assetId, String status) {
        repository.setReviewTaskStatus(assetId, status);
    }
}
