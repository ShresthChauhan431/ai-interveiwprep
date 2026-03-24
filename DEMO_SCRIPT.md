# 🎯 AI INTERVIEW PLATFORM - DEMO SCRIPT
## Full Walkthrough: 30 Minutes

---

## 📋 PRE-DEMO CHECKLIST (15 mins before)

### ✅ System Check
- [ ] Ollama running (`ollama serve`)
- [ ] Backend running on port 8081
- [ ] Frontend running on port 3002
- [ ] MySQL database connected
- [ ] Internet connection stable

### ✅ Browser Check
- [ ] Clear browser cache
- [ ] Open fresh incognito window
- [ ] Test login page loads
- [ ] Camera/Mic permissions allowed

### ✅ Backup Ready
- [ ] Pre-recorded video of interview flow (in case of issues)
- [ ] Screenshots of all features
- [ ] Fallback: Generic questions work even if Ollama slow

---

## 🎬 DEMO STRUCTURE (30 Minutes)

```
┌─────────────────────────────────────────────────────────────────┐
│  TIME    │  SECTION                      │  FOCUS              │
├─────────────────────────────────────────────────────────────────┤
│  0-2 min │  Introduction                │  Project Overview   │
│  2-5 min │  Tech Stack & Architecture   │  Technical           │
│  5-8 min │  User Registration & Login   │  Demo                │
│  8-12 min│  Resume Upload & Parsing     │  Demo + Technical   │
│ 12-18 min│  Interview Session           │  DEMO (Core)        │
│ 18-22 min│  AI Feedback & Results       │  Demo + Technical   │
│ 22-26 min│  History & Features          │  Demo                │
│ 26-28 min│  Backend Architecture        │  Technical           │
│ 28-30 min│  Conclusion & Q&A            │  Summary             │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📝 DETAILED DEMO SCRIPT

### SECTION 1: INTRODUCTION (0:00 - 2:00)
#### Slide/Opening

**SAY:**
> "Good morning/afternoon everyone. Today I'm going to demonstrate the **AI Interview Preparation Platform** - a full-stack application that helps job seekers practice technical interviews with AI-generated questions personalized to their resume."

**SHOW:**
- Point to the running application on screen

**SAY:**
> "This is a live demonstration. Let me walk you through how it works."

---

### SECTION 2: TECH STACK & ARCHITECTURE (2:00 - 5:00)
#### Show Architecture Diagram

**SAY:**
> "Before we dive into the demo, let me briefly explain the technology stack and architecture."

**SHOW:**
- Display this architecture slide:

```
┌────────────────────────────────────────────┐
│           FRONTEND (React 19)             │
│         TypeScript + Material-UI          │
│              Port: 3002                    │
└────────────────────┬───────────────────────┘
                     │ REST API
┌────────────────────▼───────────────────────┐
│          BACKEND (Spring Boot 3.2)        │
│              Java 21                      │
│              Port: 8081                   │
└────┬─────────────┬─────────────┬───────────┘
     │             │             │
     ▼             ▼             ▼
┌─────────┐  ┌──────────┐  ┌───────────┐
│ MySQL   │  │ Ollama   │  │ External  │
│   8.0   │  │ (Llama3) │  │   APIs    │
└─────────┘  └──────────┘  └───────────┘
                           • ElevenLabs
                           • AssemblyAI
                           • D-ID
```

**SAY:**
> "The backend is built with **Spring Boot 3.2** and **Java 21**, using **MySQL 8.0** for the database. The frontend uses **React 19** with **TypeScript** and **Material-UI**.
>
> The key differentiator is the AI integration - we use **Ollama with Llama 3** running locally for question generation and feedback analysis. This keeps costs near zero compared to using OpenAI or other paid APIs.
>
> We also integrate with **ElevenLabs** for text-to-speech, **AssemblyAI** for transcription, and **D-ID** for AI avatars."

---

### SECTION 3: USER REGISTRATION & LOGIN (5:00 - 8:00)
#### Live Demo

**ACTION:** Navigate to `http://localhost:3002`

**SAY:**
> "Let's start by creating an account. The application has secure JWT authentication with password encryption."

**STEP 3.1: Registration**
1. Click "Register" button
2. Fill in form:
   - Name: "Demo User"
   - Email: "demo@test.com"
   - Password: "Demo@123"
3. Click Register
4. Show successful login → redirects to Dashboard

**SAY:**
> "As you can see, registration is smooth. The password is securely hashed using BCrypt before storing in the database."

**SHOW:**
- Open browser DevTools → Network tab
- Show the JWT token in the response

**SAY:**
> "We use JWT tokens for authentication. Notice the token is returned in the response - this is stored securely and sent with every subsequent request."

---

### SECTION 4: RESUME UPLOAD & PARSING (8:00 - 12:00)
#### Live Demo

**ACTION:** On Dashboard, click "Upload Resume"

**SAY:**
> "Now let's upload a resume. The system supports PDF and DOCX files."

**STEP 4.1: Upload Resume**
1. Click upload zone
2. Select a sample PDF resume
3. Show upload progress
4. Show success message

**SAY:**
> "The resume is uploaded and text is automatically extracted using Apache PDFBox for PDFs and Apache POI for DOCX files."

**TECHNICAL NOTE (for judges):**
> "We validate files using magic-byte verification to ensure they're actually PDF/DOCX, not just renamed malicious files. The extracted text is also sanitized to prevent prompt injection attacks into our AI system."

**STEP 4.2: Show Extracted Text**
1. Click on uploaded resume
2. Show the extracted text displayed

**SAY:**
> "The system has successfully extracted the text content from the resume. This will be used to generate personalized interview questions."

**STEP 4.3: Select Job Role**
1. Show job role dropdown
2. Select "Software Engineer"
3. Show the available roles: Software Engineer, Data Scientist, DevOps Engineer, etc.

**SAY:**
> "Users can select their target job role. The AI will generate questions relevant to both their resume AND the job they're targeting."

---

### SECTION 5: INTERVIEW SESSION - CORE DEMO (12:00 - 18:00)
#### ⭐ MAIN DEMO SECTION ⭐

**SAY:**
> "Now let's start the core feature - the AI-powered interview simulation."

**STEP 5.1: Start Interview**
1. Click "Start Interview"
2. Select:
   - Resume: The one we just uploaded
   - Job Role: Software Engineer
   - Questions: 5 (for quick demo)
3. Click "Begin Interview"

**SAY:**
> "The system is now generating personalized questions using Llama 3 based on the resume content and job role. This typically takes 10-30 seconds."

**WAIT:** Show loading state (important!)

**SAY:**
> "While this is loading, let me explain what's happening behind the scenes..."

**TECHNICAL EXPLANATION:**
> "The backend is:
> 1. Taking the resume text
> 2. Building a prompt for Llama 3
> 3. Generating 5 personalized interview questions
> 4. Converting questions to speech using ElevenLabs
> 5. Saving everything to the database
>
> If Ollama fails or is slow, we have a fallback system that generates generic questions automatically."

**SHOW:** When questions load, continue...

**STEP 5.2: Question Display**
1. Show the first question on screen
2. Point out:
   - Question text
   - Category (TECHNICAL/BEHAVIORAL)
   - Difficulty (EASY/MEDIUM/HARD)

**SAY:**
> "Here we have our first AI-generated question! Notice it's personalized - it's based on the resume content I uploaded. The category is BEHAVIORAL and difficulty is MEDIUM."

**STEP 5.3: AI Avatar & TTS**
1. Show the avatar/video area
2. Click "Play Audio" button
3. Let audio play (question is read aloud)

**SAY:**
> "We use ElevenLabs for text-to-speech to read the question aloud. Optionally, we can integrate D-ID avatars for a more realistic interviewer experience."

**STEP 5.4: Record Answer**
1. Click "Start Recording" button
2. Allow camera/mic if prompted
3. Record a 15-20 second answer
4. Click "Stop Recording"
5. Show preview of recorded video

**SAY:**
> "Candidates record their answers using the browser's MediaRecorder API. The video is compressed and stored locally."

**TECHNICAL NOTE:**
> "Video recording happens client-side, then uploaded to our server. We handle chunked uploads for larger files."

**STEP 5.5: Submit Answer**
1. Click "Submit Answer"
2. Show upload progress
3. Show "Answer Saved" confirmation
4. Move to Question 2

**SAY:**
> "The answer is uploaded and saved. Let's quickly do one more question to show the flow."

**STEP 5.6: Question 2 (Quick)**
1. Show Question 2
2. Record a quick answer (5-10 seconds)
3. Submit

**STEP 5.7: Complete Interview**
1. After 2 questions, click "Finish Interview"
2. Show confirmation dialog
3. Click "Complete"

**SAY:**
> "The interview is complete! Now the system will analyze the responses and generate AI feedback."

---

### SECTION 6: AI FEEDBACK & RESULTS (18:00 - 22:00)

**ACTION:** Show the processing/loading state

**SAY:**
> "The system is now analyzing the responses using AssemblyAI for transcription and Llama 3 for feedback generation. This typically takes 15-30 seconds."

**WAIT:** Let it process...

**STEP 6.1: Feedback Display**
1. Show the completed interview screen
2. Highlight: Overall Score (e.g., 7.5/10)

**SAY:**
> "The AI has analyzed the responses and generated a comprehensive feedback report."

**STEP 6.2: Show Detailed Feedback**
1. Scroll to show:
   - **Strengths** (what candidate did well)
   - **Areas for Improvement** (weaknesses)
   - **Recommendations** (how to improve)

**SAY:**
> "The feedback includes:
> - **Strengths**: Specific positive points about the answers
> - **Areas for Improvement**: Constructive feedback
> - **Recommendations**: Actionable advice for future interviews"

**TECHNICAL NOTE:**
> "The feedback is generated by Llama 3, which analyzes the transcribed text and provides scores based on clarity, relevance, and depth of answers."

**STEP 6.3: Show Transcription**
1. Click on one of the responses
2. Show the video playback
3. Show the transcribed text below

**SAY:**
> "Each response is transcribed using AssemblyAI's speech-to-text. Users can review both the video and text of their answers."

---

### SECTION 7: HISTORY & ADDITIONAL FEATURES (22:00 - 26:00)

**STEP 7.1: Interview History**
1. Navigate to History page
2. Show list of completed interviews
3. Show score trends

**SAY:**
> "Users can track their progress over time. Each interview is saved with its score and feedback."

**STEP 7.2: Dashboard Stats**
1. Show Dashboard with statistics
2. Highlight:
   - Total interviews
   - Average score
   - Recent activity

**STEP 7.3: Proctoring (Optional Demo)**
> **Note:** Only demonstrate if time permits and you want to show the proctoring feature

**SAY:**
> "We also have a proctoring system that monitors candidate attention during the interview."

**DEMO PROCTORING:**
1. Start a new interview (or use existing)
2. Switch to another browser tab
3. Show warning appears
4. Switch back - warning clears
5. (Don't trigger full disqualification)

**SAY:**
> "The proctoring system detects:
> - Tab switches (Page Visibility API)
> - Window blur events
> - Face detection using TensorFlow.js
>
> After 3 violations, the interview is automatically terminated."

---

### SECTION 8: BACKEND ARCHITECTURE DEEP DIVE (26:00 - 28:00)
#### Technical Discussion

**SAY:**
> "Let me show you some key technical aspects of the backend."

**STEP 8.1: Show API Endpoints**
1. Open Postman or show Swagger-like documentation
2. Demonstrate a few key API calls

**SAY:**
> "The backend exposes RESTful APIs with proper:
> - JWT authentication
> - Rate limiting (to prevent abuse)
> - Input validation
> - Error handling"

**STEP 8.2: Database Schema**
1. Show MySQL Workbench or query results
2. Display key tables: users, resumes, interviews, questions, responses, feedbacks

**SAY:**
> "We use Flyway for database migrations, ensuring version-controlled schema changes. The schema is normalized with proper relationships."

**STEP 8.3: Security Features**
1. Show SecurityConfig in code (optional)

**SAY:**
> "Security highlights:
> - Passwords hashed with BCrypt
> - JWT tokens with expiration
> - CORS configured for specific origins
> - Rate limiting on expensive endpoints
> - Input sanitization for AI prompts"

---

### SECTION 9: CONCLUSION (28:00 - 30:00)

**SAY:**
> "Let me summarize what we've built."

**SHOW:**
- Summary slide with key points:

```
┌─────────────────────────────────────────────┐
│           PROJECT SUMMARY                    │
├─────────────────────────────────────────────┤
│ ✅ User Authentication (JWT + BCrypt)      │
│ ✅ Resume Upload & Text Extraction          │
│ ✅ AI Question Generation (Ollama/Llama 3) │
│ ✅ Text-to-Speech (ElevenLabs)             │
│ ✅ Video Recording (Browser MediaRecorder)  │
│ ✅ Speech-to-Text (AssemblyAI)              │
│ ✅ AI-Powered Feedback & Scoring           │
│ ✅ Interview Proctoring System              │
│ ✅ Progress Tracking & History              │
│                                             │
│ TECH STACK:                                 │
│ Backend: Spring Boot, Java 21, MySQL        │
│ Frontend: React 19, TypeScript, MUI        │
│ AI: Ollama (Llama 3), ElevenLabs,          │
│     AssemblyAI, D-ID                        │
└─────────────────────────────────────────────┘
```

**SAY:**
> "This project demonstrates:
> - Full-stack development with modern frameworks
> - Integration with multiple AI services
> - Secure authentication and data handling
> - Real-time features (video recording, SSE)
> - Production-ready architecture with resilience patterns"

**SAY:**
> "Thank you for your attention. I'm happy to answer any questions."

**OPEN FLOOR FOR Q&A** 📢

---

## 🎯 KEY TALKING POINTS (Memorize These)

| # | Point | When to Use |
|---|-------|-------------|
| 1 | "Questions are AI-generated from YOUR resume" | During interview start |
| 2 | "We use LOCAL Llama 3 - almost zero API cost" | Tech stack discussion |
| 3 | "Everything runs in browser - no software install needed" | User experience |
| 4 | "Fallback system ensures interview works even if AI fails" | If Ollama is slow |
| 5 | "Proctoring prevents cheating - real interview practice" | About integrity |

---

## ⚠️ TROUBLESHOOTING (If Things Go Wrong)

| Problem | Solution |
|---------|----------|
| Ollama slow/not responding | Show fallback questions - explain the system |
| Video not recording | Use pre-recorded clip as backup |
| Login fails | Quick demo with pre-created account |
| Network issues | Switch to showing screenshots |

---

## 📱 BACKUP PLAN (Pre-Recorded Video)

**If live demo fails:**
1. Say: "Let me show you a recorded demonstration instead"
2. Play pre-recorded video of full flow
3. Continue with technical explanation

**Pre-record this:**
1. Full interview with 3 questions
2. Complete feedback show
3. History page overview

---

## ✅ FINAL CHECKLIST BEFORE DEMO DAY

- [ ] Tested entire flow at least 3 times
- [ ] Camera and microphone working
- [ ] Sample resume PDF ready
- [ ] Pre-recorded backup video ready
- [ ] Screenshots of all features ready
- [ ] Laptop fully charged
- [ ] Internet connection tested
- [ ] Backup internet (mobile hotspot) ready

---

**Good luck with your presentation! 🎉**
