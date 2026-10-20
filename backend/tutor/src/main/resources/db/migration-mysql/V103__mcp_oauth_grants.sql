-- OAuth credentials are hashes only; no app/device tokens are stored or forwarded.
CREATE TABLE mcp_oauth_grants (
    id VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id BIGINT NULL,
    payload JSON NOT NULL,
    connected BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_mcp_oauth_user (user_id, connected, expires_at),
    INDEX idx_mcp_oauth_expiry (expires_at),
    CONSTRAINT fk_mcp_oauth_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Rotation replay evidence persists for the connection lifetime and cascades on expiry/deletion.
CREATE TABLE mcp_oauth_used_refresh_tokens (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    grant_id VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    INDEX idx_mcp_used_refresh_grant (grant_id),
    CONSTRAINT fk_mcp_used_refresh_grant FOREIGN KEY (grant_id) REFERENCES mcp_oauth_grants(id) ON DELETE CASCADE
);
