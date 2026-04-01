-- V11: Make video_url in responses table nullable
-- The cleanup process sets video_url to null after deleting the raw video from storage
-- to save space, but the column was originally defined as NOT NULL.

ALTER TABLE responses MODIFY COLUMN video_url VARCHAR(2048) NULL;
