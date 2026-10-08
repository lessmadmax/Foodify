ALTER TABLE members ADD COLUMN ai_consent_version VARCHAR(60);
ALTER TABLE members ADD COLUMN ai_consented_at BIGINT;
ALTER TABLE members ADD COLUMN ai_revoked_at BIGINT;
CREATE TABLE ai_consent_events (
 id VARCHAR(36) PRIMARY KEY,
 member_id VARCHAR(36) NOT NULL,
 policy_version VARCHAR(60) NOT NULL,
 action VARCHAR(20) NOT NULL,
 occurred_at BIGINT NOT NULL,
 FOREIGN KEY(member_id) REFERENCES members(id) ON DELETE CASCADE
);
