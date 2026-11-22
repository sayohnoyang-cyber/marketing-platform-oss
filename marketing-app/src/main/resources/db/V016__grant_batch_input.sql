-- 新任务可在不同实例上读取原始输入，完成后释放文件内容。
ALTER TABLE grant_batch ADD COLUMN input_file MEDIUMBLOB NULL;
