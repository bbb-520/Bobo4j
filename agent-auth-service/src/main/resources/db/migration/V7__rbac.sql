ALTER TABLE app_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE app_user ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE app_user ADD COLUMN last_login_at DATETIME(3) NULL;

CREATE TABLE app_role (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 is_system BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_role_code (tenant_id,code),
 UNIQUE KEY uk_role_tenant (tenant_id,id)
);
CREATE TABLE app_permission (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(96) NOT NULL UNIQUE,
 name VARCHAR(128) NOT NULL,
 module VARCHAR(32) NOT NULL
);
CREATE TABLE app_user_role (
 tenant_id VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 granted_by BIGINT NULL,
 granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (tenant_id,user_id,role_id),
 KEY idx_user_role_role (role_id,user_id),
 CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id),
 CONSTRAINT fk_user_role_tenant FOREIGN KEY (tenant_id,role_id) REFERENCES app_role(tenant_id,id)
);
CREATE TABLE app_role_permission (
 role_id BIGINT NOT NULL,
 permission_id BIGINT NOT NULL,
 PRIMARY KEY (role_id,permission_id),
 KEY idx_role_permission_permission (permission_id,role_id),
 CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES app_role(id),
 CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES app_permission(id)
);
CREATE TABLE authz_audit_log (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 actor_id BIGINT NULL,
 target VARCHAR(128) NOT NULL,
 action VARCHAR(64) NOT NULL,
 before_value TEXT NOT NULL,
 after_value TEXT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_authz_audit_tenant (tenant_id,id)
);
INSERT INTO app_role(tenant_id,code,name,is_system) VALUES
 ('local','USER','普通用户',TRUE),('local','ADMIN','管理员',TRUE),('local','SUPER_ADMIN','超级管理员',TRUE);
INSERT INTO app_permission(code,name,module) VALUES
 ('chat:use','聊天','chat'),('rag:document:manage','个人知识库','rag'),
 ('media:generate','图像与媒体','media'),('content:manage','个人内容','content'),
 ('agent:execute','智能体任务','agent'),('profile:manage','个人资料','account'),
 ('model:manage','个人模型配置','account'),('wallet:read','个人钱包','billing'),
 ('payment:manage','个人支付订单','billing'),('user:read','查看用户','admin'),
 ('user:disable','启用与禁用用户','admin'),('billing:refund','运营退款','billing'),
 ('billing:reconcile','计费核对','billing'),('role:assign','角色授权','admin'),
 ('role:permission:set','角色与权限配置','admin'),('audit:read','查看授权审计','admin');
INSERT INTO app_role_permission(role_id,permission_id)
 SELECT r.id,p.id FROM app_role r CROSS JOIN app_permission p
 WHERE r.tenant_id='local' AND (
 (r.code='USER' AND p.module NOT IN ('admin') AND p.code NOT IN ('billing:refund','billing:reconcile')) OR
 (r.code='ADMIN' AND p.code IN ('user:read','user:disable','billing:refund','billing:reconcile','audit:read')) OR
 (r.code='SUPER_ADMIN' AND p.code IN ('user:read','user:disable','billing:refund','billing:reconcile','audit:read','role:assign','role:permission:set')));
INSERT INTO app_user_role(tenant_id,user_id,role_id)
 SELECT 'local',u.id,r.id FROM app_user u JOIN app_role r ON r.tenant_id='local' AND r.code='USER'
 WHERE NOT EXISTS(SELECT 1 FROM app_user_role ur WHERE ur.tenant_id='local' AND ur.user_id=u.id AND ur.role_id=r.id);
