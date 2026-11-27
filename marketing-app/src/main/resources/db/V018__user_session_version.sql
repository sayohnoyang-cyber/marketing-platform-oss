-- 改密和撤销旧会话在同一条用户记录上原子提交。
ALTER TABLE iam_user ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
