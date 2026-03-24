┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              BACKEND WORKFLOW - INTERVIEW PROCESS                          │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

START INTERVIEW FLOW
════════════════════

    ┌─────────────┐
    │ User Clicks │
    │ Start       │
    └──────┬──────┘
           │
           ▼
┌──────────────────────────────┐
│ POST /api/interviews/start   │
│ (with resumeId, jobRoleId)   │
└──────────────┬───────────────┘
               │
               ▼
    ┌─────────────────────────────────┐
    │ 1. Validate User & Resume       │
    │ 2. Extract Resume Text          │
    │ 3. Validate Job Role             │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 4. Create Interview Entity      │
    │    Status: CREATED              │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 5. Call OllamaService          │
    │    generateQuestions()          │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 6. Ollama (Llama 3)            │
    │    Generate JSON Questions     │
    │    Based on Resume + Job Role  │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 7. Save Questions to DB        │
    │    + Generate TTS Audio         │
    │    (ElevenLabs API)             │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 8. Update Interview Status      │
    │    to IN_PROGRESS               │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ Return InterviewDTO             │
    │ (Questions + Audio URLs)        │
    └─────────────────────────────────┘


INTERVIEW SESSION FLOW
══════════════════════

    ┌─────────────────────────────┐
    │ Frontend Displays Question │
    │ + Plays TTS Audio           │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ User Records Video Answer   │
    │ (MediaRecorder API)         │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ Upload Video to Storage     │
    │ (via presigned URL or API)  │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ POST /confirm-upload        │
    │ Save Response to DB         │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ Trigger AssemblyAI          │
    │ Transcription (Async)      │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ Move to Next Question       │
    │ OR Complete Interview       │
    └─────────────────────────────┘


COMPLETE INTERVIEW FLOW
═══════════════════════

    ┌─────────────────────────────┐
    │ User Clicks Complete        │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ POST /api/interviews/{id}/  │
    │ complete                    │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────────┐
    │ 1. Validate All Responses      │
    │    Received                    │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 2. Update Status: PROCESSING    │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 3. Call AIFeedbackService      │
    │    generateFeedbackAsync()      │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 4. Analyze All Transcriptions  │
    │    Using Ollama (Llama 3)       │
    │    Generate:                    │
    │    • Overall Score              │
    │    • Strengths                  │
    │    • Weaknesses                │
    │    • Recommendations           │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ 5. Save Feedback to DB          │
    │ 6. Update Status: COMPLETED     │
    └────────────────┬────────────────┘
                     │
                     ▼
    ┌─────────────────────────────────┐
    │ Return Interview with Feedback  │
    └─────────────────────────────────┘


PROCTORING WORKFLOW
════════════════════

    ┌─────────────────────────────┐
    │ InterviewRoom Component    │
    │ Activates Proctoring       │
    └──────────────┬──────────────┘
                   │
     ┌─────────────┼─────────────┐
     │             │             │
     ▼             ▼             ▼
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Page    │  │ Window  │  │Face     │
│Visibility│  │ Focus   │  │Detection│
│  API    │  │ Events  │  │(BlazeFace)│
└────┬────┘  └────┬────┘  └────┬────┘
     │             │             │
     └─────────────┼─────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ Track Violations            │
    │ (Counter + Cooldown)        │
    └──────────────┬──────────────┘
                   │
                   ▼
    ┌─────────────────────────────┐
    │ If Violations >= 3         │
    │ Call terminateInterview()   │
    │ Status: DISQUALIFIED        │
    └─────────────────────────────┘
