package com.qingmei.reviewplatform.repository;

import com.qingmei.reviewplatform.model.Asset;
import com.qingmei.reviewplatform.model.ReviewTask;
import com.qingmei.reviewplatform.model.ShareLink;
import com.qingmei.reviewplatform.model.UserAccount;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void migrate() {
        List<String> queries = List.of(
                """
                CREATE TABLE IF NOT EXISTS assets (
                    id UUID PRIMARY KEY,
                    owner_username TEXT NOT NULL DEFAULT 'system',
                    original_name TEXT NOT NULL,
                    stored_name TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    storage_path TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS share_links (
                    id UUID PRIMARY KEY,
                    asset_id UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
                    token TEXT NOT NULL UNIQUE,
                    annotations_json TEXT NOT NULL DEFAULT '[]',
                    expires_at TIMESTAMPTZ NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS review_tasks (
                    id UUID PRIMARY KEY,
                    asset_id UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
                    status TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS users (
                    id UUID PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
                    banned BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "ALTER TABLE assets ADD COLUMN IF NOT EXISTS owner_username TEXT NOT NULL DEFAULT 'system';",
                "ALTER TABLE share_links ADD COLUMN IF NOT EXISTS annotations_json TEXT NOT NULL DEFAULT '[]';",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE users ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;",
                "UPDATE users SET is_admin = TRUE WHERE username = 'admin';",
                "CREATE INDEX IF NOT EXISTS idx_assets_owner_created_at ON assets(owner_username, created_at DESC);",
                "CREATE INDEX IF NOT EXISTS idx_assets_created_at ON assets(created_at DESC);",
                "CREATE INDEX IF NOT EXISTS idx_share_links_token ON share_links(token);",
                "CREATE INDEX IF NOT EXISTS idx_review_tasks_asset_id ON review_tasks(asset_id);",
                "CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);"
        );
        queries.forEach(jdbcTemplate::execute);
    }

    public void createUser(UserAccount user) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, is_admin, banned, created_at) VALUES (?::uuid, ?, ?, ?, ?, ?)",
                user.getId(), user.getUsername(), user.getPasswordHash(), user.isAdmin(), user.isBanned(), user.getCreatedAt()
        );
    }

    public UserAccount getUserByUsername(String username) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash, is_admin, banned, created_at FROM users WHERE username = ?",
                    this::mapUser,
                    username
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("user not found");
        }
    }

    public List<UserAccount> listUsers() {
        return jdbcTemplate.query(
                "SELECT id, username, password_hash, is_admin, banned, created_at FROM users ORDER BY created_at DESC",
                this::mapUser
        );
    }

    public void updateUserAdmin(String username, boolean admin) {
        int rows = jdbcTemplate.update("UPDATE users SET is_admin = ? WHERE username = ?", admin, username);
        if (rows == 0) {
            throw new NotFoundException("user not found");
        }
    }

    public void updateUserBanned(String username, boolean banned) {
        int rows = jdbcTemplate.update("UPDATE users SET banned = ? WHERE username = ?", banned, username);
        if (rows == 0) {
            throw new NotFoundException("user not found");
        }
    }

    public void createAsset(Asset a) {
        jdbcTemplate.update(
                "INSERT INTO assets (id, owner_username, original_name, stored_name, mime_type, size_bytes, storage_path, created_at) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?)",
                a.getId(), a.getOwnerUsername(), a.getOriginalName(), a.getStoredName(), a.getMimeType(), a.getSizeBytes(), a.getStoragePath(), a.getCreatedAt()
        );
    }

    public Asset getAssetById(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, owner_username, original_name, stored_name, mime_type, size_bytes, storage_path, created_at FROM assets WHERE id = ?::uuid",
                    this::mapAsset,
                    id
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("asset not found");
        }
    }

    public Asset getAssetByIdAndOwner(String id, String ownerUsername) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, owner_username, original_name, stored_name, mime_type, size_bytes, storage_path, created_at FROM assets WHERE id = ?::uuid AND owner_username = ?",
                    this::mapAsset,
                    id,
                    ownerUsername
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("asset not found");
        }
    }

    public List<Asset> listAssets(String ownerUsername, int limit, int offset) {
        return jdbcTemplate.query(
                "SELECT id, owner_username, original_name, stored_name, mime_type, size_bytes, storage_path, created_at FROM assets WHERE owner_username = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                this::mapAsset,
                ownerUsername,
                limit,
                offset
        );
    }

    public List<Asset> listAllAssets() {
        return jdbcTemplate.query(
                "SELECT id, owner_username, original_name, stored_name, mime_type, size_bytes, storage_path, created_at FROM assets ORDER BY created_at DESC",
                this::mapAsset
        );
    }

    public void deleteAssetByIdAndOwner(String id, String ownerUsername) {
        int rows = jdbcTemplate.update(
                "DELETE FROM assets WHERE id = ?::uuid AND owner_username = ?",
                id,
                ownerUsername
        );
        if (rows == 0) {
            throw new NotFoundException("asset not found");
        }
    }

    public void createShareLink(ShareLink s) {
        jdbcTemplate.update(
                "INSERT INTO share_links (id, asset_id, token, annotations_json, expires_at, created_at) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)",
                s.getId(), s.getAssetId(), s.getToken(), s.getAnnotationsJson(), s.getExpiresAt(), s.getCreatedAt()
        );
    }

    public ShareLink getShareByToken(String token) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, asset_id, token, annotations_json, expires_at, created_at FROM share_links WHERE token = ?",
                    this::mapShare,
                    token
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("share link not found");
        }
    }

    public void updateShareAnnotations(String token, String annotationsJson) {
        int rows = jdbcTemplate.update(
                "UPDATE share_links SET annotations_json = ? WHERE token = ?",
                annotationsJson,
                token
        );
        if (rows == 0) {
            throw new NotFoundException("share link not found");
        }
    }

    public void createReviewTask(ReviewTask t) {
        jdbcTemplate.update(
                "INSERT INTO review_tasks (id, asset_id, status, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?, ?, ?)",
                t.getId(), t.getAssetId(), t.getStatus(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }

    public void setReviewTaskStatus(String assetId, String status) {
        jdbcTemplate.update(
                "UPDATE review_tasks SET status = ?, updated_at = ? WHERE asset_id = ?::uuid",
                status,
                OffsetDateTime.now(ZoneOffset.UTC),
                assetId
        );
    }

    private Asset mapAsset(ResultSet rs, int rowNum) throws SQLException {
        Asset a = new Asset();
        a.setId(rs.getString("id"));
        a.setOwnerUsername(rs.getString("owner_username"));
        a.setOriginalName(rs.getString("original_name"));
        a.setStoredName(rs.getString("stored_name"));
        a.setMimeType(rs.getString("mime_type"));
        a.setSizeBytes(rs.getLong("size_bytes"));
        a.setStoragePath(rs.getString("storage_path"));
        a.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return a;
    }

    private ShareLink mapShare(ResultSet rs, int rowNum) throws SQLException {
        ShareLink s = new ShareLink();
        s.setId(rs.getString("id"));
        s.setAssetId(rs.getString("asset_id"));
        s.setToken(rs.getString("token"));
        s.setAnnotationsJson(rs.getString("annotations_json"));
        s.setExpiresAt(rs.getObject("expires_at", OffsetDateTime.class));
        s.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return s;
    }

    private UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
        UserAccount user = new UserAccount();
        user.setId(rs.getString("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setAdmin(rs.getBoolean("is_admin"));
        user.setBanned(rs.getBoolean("banned"));
        user.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return user;
    }
}
