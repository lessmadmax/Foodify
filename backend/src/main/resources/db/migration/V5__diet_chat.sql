CREATE TABLE chat_turns (
 id VARCHAR(36) PRIMARY KEY, member_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(100) NOT NULL, question VARCHAR(2000) NOT NULL,
 answer TEXT, status VARCHAR(20) NOT NULL, error_code VARCHAR(100),
 evidence TEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(member_id,request_key),
 FOREIGN KEY(member_id) REFERENCES members(id) ON DELETE CASCADE
);
CREATE INDEX chat_member_time ON chat_turns(member_id,created_at);
