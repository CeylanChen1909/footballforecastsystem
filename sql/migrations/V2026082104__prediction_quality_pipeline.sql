-- Point-in-time prediction quality pipeline: resumable historical backfill.
-- The service also creates this table defensively for existing installations.
CREATE TABLE IF NOT EXISTS t_crawler_backfill_job (
    job_name VARCHAR(64) NOT NULL PRIMARY KEY,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    next_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL,
    processed_days INT NOT NULL DEFAULT 0,
    processed_matches INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
