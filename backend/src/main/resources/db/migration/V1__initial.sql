CREATE TABLE members (
 id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, goals TEXT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE sessions (
 id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL,
 access_hash VARCHAR(64) NOT NULL UNIQUE, refresh_hash VARCHAR(64) NOT NULL UNIQUE,
 access_expires BIGINT NOT NULL, refresh_expires BIGINT NOT NULL,
 FOREIGN KEY(member_id) REFERENCES members(id) ON DELETE CASCADE
);
CREATE TABLE foods (
 id VARCHAR(100) PRIMARY KEY, name VARCHAR(200) NOT NULL, aliases VARCHAR(1000) NOT NULL,
 basis_grams DECIMAL(12,3) NOT NULL,
 kcal DECIMAL(12,3), carbs DECIMAL(12,3), protein DECIMAL(12,3), fat DECIMAL(12,3),
 source VARCHAR(500) NOT NULL, source_version VARCHAR(100) NOT NULL
);
CREATE TABLE meals (
 id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL, eaten_at BIGINT NOT NULL,
 status VARCHAR(30) NOT NULL, version BIGINT NOT NULL,
 items TEXT NOT NULL, capture_info TEXT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(member_id, request_key),
 FOREIGN KEY(member_id) REFERENCES members(id) ON DELETE CASCADE
);
CREATE INDEX meals_member_time ON meals(member_id,eaten_at);
CREATE TABLE photos (
 id VARCHAR(36) PRIMARY KEY, meal_id VARCHAR(36) NOT NULL,
 storage_key VARCHAR(100) NOT NULL, FOREIGN KEY(meal_id) REFERENCES meals(id) ON DELETE CASCADE
);
CREATE TABLE analyses (
 id VARCHAR(36) PRIMARY KEY, meal_id VARCHAR(36) NOT NULL,
 expected_version BIGINT NOT NULL, status VARCHAR(30) NOT NULL,
 error_code VARCHAR(100), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 FOREIGN KEY(meal_id) REFERENCES meals(id) ON DELETE CASCADE
);
CREATE TABLE feedback (
 id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL,
 from_date VARCHAR(10) NOT NULL, to_date VARCHAR(10) NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL, content TEXT NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(member_id) REFERENCES members(id) ON DELETE CASCADE
);
CREATE TABLE ai_usage (
 id VARCHAR(36) PRIMARY KEY, month_key VARCHAR(7) NOT NULL,
 kind VARCHAR(30) NOT NULL, model VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL, input_tokens BIGINT NOT NULL, output_tokens BIGINT NOT NULL,
 elapsed_ms BIGINT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE ai_budget (
 month_key VARCHAR(7) PRIMARY KEY, requests_used BIGINT NOT NULL
);
CREATE TABLE photo_deletions (storage_key VARCHAR(100) PRIMARY KEY);
