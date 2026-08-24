CREATE TABLE IF NOT EXISTS t_user_legal_consent (
    user_id BIGINT NOT NULL,
    consent_version VARCHAR(32) NOT NULL,
    agreed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    PRIMARY KEY (user_id),
    KEY idx_user_legal_consent_version (consent_version, agreed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
