ALTER TABLE app_user ADD COLUMN public_user_id CHAR(36) NULL;
ALTER TABLE app_user ADD COLUMN qq_email VARCHAR(254) NULL;
-- Preserve Java UUID.nameUUIDFromBytes("bobo:user:" + id), i.e. MD5 UUID v3.
UPDATE app_user SET public_user_id=LOWER(CONCAT(
 SUBSTRING(MD5(CONCAT('bobo:user:',id)),1,8),'-',SUBSTRING(MD5(CONCAT('bobo:user:',id)),9,4),'-',
 '3',SUBSTRING(MD5(CONCAT('bobo:user:',id)),14,3),'-',
 SUBSTRING('89ab',MOD(CONV(SUBSTRING(MD5(CONCAT('bobo:user:',id)),17,1),16,10),4)+1,1),
 SUBSTRING(MD5(CONCAT('bobo:user:',id)),18,3),'-',SUBSTRING(MD5(CONCAT('bobo:user:',id)),21,12)));
CREATE UNIQUE INDEX uk_user_public_id ON app_user(public_user_id);
CREATE UNIQUE INDEX uk_user_qq_email ON app_user(qq_email);
CREATE TABLE email_code (
 email VARCHAR(254) NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 code_hash CHAR(64) NOT NULL,
 expires_at TIMESTAMP(3) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 consumed BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(email,purpose)
);
CREATE TABLE email_send_limit (
 limit_key VARCHAR(128) PRIMARY KEY,
 count_value INT NOT NULL DEFAULT 0,
 reset_at TIMESTAMP(3) NOT NULL
);
