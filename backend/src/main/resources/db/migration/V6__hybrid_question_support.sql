-- V6: Add hybrid question generation support
-- Adds fields for tracking dynamic question generation during interviews

-- Add total_questions to interviews table for hybrid mode
ALTER TABLE interviews
ADD COLUMN total_questions INT NULL;

-- Add generation_mode to questions table to track if pre-generated or dynamic
ALTER TABLE questions
ADD COLUMN generation_mode VARCHAR(20) NULL;

-- Add generated_after_question_id to questions for dynamic question lineage
ALTER TABLE questions
ADD COLUMN generated_after_question_id BIGINT NULL;

-- Add index for efficient lookup of questions by interview and question number
CREATE INDEX idx_questions_interview_number ON questions(interview_id, question_number);

-- Add foreign key constraint for generated_after_question_id (self-referencing)
ALTER TABLE questions
ADD CONSTRAINT fk_questions_generated_after
FOREIGN KEY (generated_after_question_id) REFERENCES questions(id)
ON DELETE SET NULL;
