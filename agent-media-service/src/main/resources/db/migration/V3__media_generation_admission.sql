-- At most one queued/running image job per owner across all application instances.
-- Keep one outstanding legacy task per owner before introducing the uniqueness constraint.
UPDATE image_job j JOIN (
 SELECT id,ROW_NUMBER() OVER (PARTITION BY tenant_id,user_id ORDER BY created_at,id) AS rn
 FROM image_job WHERE status IN ('QUEUED','PROCESSING')
) ranked ON j.id=ranked.id
SET j.status='FAILED',j.error_message='升级时取消重复在途任务，请重新提交',j.completed_at=NOW(3),j.updated_at=NOW(3)
WHERE ranked.rn>1;
ALTER TABLE image_job ADD COLUMN active_slot TINYINT GENERATED ALWAYS AS
 (CASE WHEN status IN ('QUEUED','PROCESSING') THEN 1 ELSE NULL END) VIRTUAL;
CREATE UNIQUE INDEX uk_image_job_active_owner ON image_job(tenant_id,user_id,active_slot);
