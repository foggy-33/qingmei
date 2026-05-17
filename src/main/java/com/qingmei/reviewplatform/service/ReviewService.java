package com.qingmei.reviewplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingmei.reviewplatform.config.AppProperties;
import com.qingmei.reviewplatform.model.Asset;
import com.qingmei.reviewplatform.model.ReviewTask;
import com.qingmei.reviewplatform.model.ShareLink;
import com.qingmei.reviewplatform.model.UserAccount;
import com.qingmei.reviewplatform.repository.ReviewRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class ReviewService {

    public static final String QUEUE_REVIEW_JOBS = "review:jobs";

    private final AppProperties appProperties;
    private final ReviewRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, Object> transcodeLocks = new ConcurrentHashMap<>();
    private final Set<String> failedTranscodes = ConcurrentHashMap.newKeySet();

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
        scheduleVideoTranscode(asset);
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

    public Asset getAssetForAdmin(String id) {
        return repository.getAssetById(id);
    }

    public void deleteAsset(String ownerUsername, String assetId) {
        Asset asset = repository.getAssetByIdAndOwner(assetId, ownerUsername);
        repository.deleteAssetByIdAndOwner(assetId, ownerUsername);
        redisTemplate.delete("asset:" + ownerUsername + ":" + assetId);
        try {
            Files.deleteIfExists(Path.of(asset.getStoragePath()));
        } catch (IOException ignored) {
        }
        deleteVideoVariants(assetId);
    }

    public record PlaybackFile(Path path, String mimeType) {
    }

    public PlaybackFile playbackFile(Asset asset, String quality) {
        if (!isVideo(asset) || !"1080p".equals(quality)) {
            return new PlaybackFile(Path.of(asset.getStoragePath()), asset.getMimeType());
        }

        Path variantPath = videoVariantPath(asset.getId(), "1080p");
        if (Files.exists(variantPath)) {
            return new PlaybackFile(variantPath, "video/mp4");
        }

        scheduleVideoTranscode(asset);
        return new PlaybackFile(Path.of(asset.getStoragePath()), asset.getMimeType());
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
            Files.createDirectories(videoVariantsDir());
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

    public Map<String, Object> adminOverview() {
        List<UserAccount> users = repository.listUsers();
        List<Asset> assets = repository.listAllAssets();
        Map<String, List<Asset>> assetsByOwner = assets.stream()
                .collect(Collectors.groupingBy(Asset::getOwnerUsername, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> userItems = new ArrayList<>();
        long totalBytes = 0;
        long totalVideos = 0;
        for (UserAccount user : users) {
            List<Asset> userAssets = assetsByOwner.getOrDefault(user.getUsername(), List.of());
            long storageBytes = userAssets.stream().mapToLong(Asset::getSizeBytes).sum();
            long videoCount = userAssets.stream().filter(this::isVideo).count();
            totalBytes += storageBytes;
            totalVideos += videoCount;

            userItems.add(Map.of(
                    "id", user.getId(),
                    "username", user.getUsername(),
                    "created_at", user.getCreatedAt(),
                    "admin", user.isAdmin() || "admin".equals(user.getUsername()),
                    "banned", user.isBanned(),
                    "asset_count", userAssets.size(),
                    "video_count", videoCount,
                    "storage_bytes", storageBytes,
                    "assets", userAssets.stream().map(this::adminAssetItem).toList()
            ));
        }

        return Map.of(
                "total_users", users.size(),
                "total_assets", assets.size(),
                "total_videos", totalVideos,
                "total_storage_bytes", totalBytes,
                "users", userItems
        );
    }

    private Map<String, Object> adminAssetItem(Asset asset) {
        return Map.of(
                "id", asset.getId(),
                "owner_username", asset.getOwnerUsername(),
                "original_name", asset.getOriginalName(),
                "mime_type", asset.getMimeType(),
                "size_bytes", asset.getSizeBytes(),
                "created_at", asset.getCreatedAt()
        );
    }

    private boolean isVideo(Asset asset) {
        return String.valueOf(asset.getMimeType()).startsWith("video/");
    }

    private Path videoVariantsDir() {
        return Path.of(appProperties.getStorageDir()).resolve("variants");
    }

    private Path videoVariantPath(String assetId, String quality) {
        return videoVariantsDir().resolve(assetId).resolve(quality + ".mp4");
    }

    private void scheduleVideoTranscode(Asset asset) {
        if (!isVideo(asset)) {
            return;
        }

        Path target = videoVariantPath(asset.getId(), "1080p");
        if (Files.exists(target)) {
            return;
        }

        String key = asset.getId() + ":1080p";
        if (failedTranscodes.contains(key)) {
            return;
        }

        Object lock = new Object();
        if (transcodeLocks.putIfAbsent(key, lock) != null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (!Files.exists(target)) {
                    transcode1080p(Path.of(asset.getStoragePath()), target);
                }
                failedTranscodes.remove(key);
            } catch (Exception ignored) {
                failedTranscodes.add(key);
            } finally {
                transcodeLocks.remove(key, lock);
            }
        });
    }

    private void transcode1080p(Path source, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temp);

        Process process = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-i", source.toString(),
                "-vf", "scale=-2:min(1080\\,ih)",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-maxrate", "5000k",
                "-bufsize", "10000k",
                "-c:a", "aac",
                "-b:a", "160k",
                "-movflags", "+faststart",
                temp.toString()
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

        int code = process.waitFor();
        if (code != 0 || !Files.exists(temp)) {
            Files.deleteIfExists(temp);
            throw new IOException("ffmpeg transcode failed");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void deleteVideoVariants(String assetId) {
        Path dir = videoVariantsDir().resolve(assetId);
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }
}
