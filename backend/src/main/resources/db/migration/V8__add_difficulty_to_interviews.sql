-- V8: Add missing 'difficulty' column to interviews table.
-- The Interview entity defines this field as VARCHAR(10), nullable.
-- Used to store difficulty level: easy, medium, hard.
ALTER TABLE interviews ADD COLUMN difficulty VARCHAR(10) NULL;
