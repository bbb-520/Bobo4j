-- F03：交付阶段的恢复必须能终止。
--
-- 供应商回执写入后，复制/结算仍可能因结果地址永久 404、内容超限或对象存储长期不可用而失败。
-- 旧实现对此一律 defer，每 30 秒回队列，且 result_ready=TRUE 会绕过过期清理，
-- 导致任务永久占用用户与全局准入名额。
--
-- 只新增列与索引，不改动历史迁移，也不改变既有状态取值：
--   delivery_attempts       交付重试次数，仅统计"已有回执后的交付失败"，
--                           与 attempt_count（领取次数）区分，避免混淆两类重试。
--   idx_image_job_delivery_review
--                           人工核对入口：status='FAILED' AND result_ready=TRUE
--                           即"已消费供应商调用但未交付成功"，可据此查询与处置。
ALTER TABLE image_job
 ADD COLUMN delivery_attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_image_job_delivery_review ON image_job(status, result_ready, completed_at);
