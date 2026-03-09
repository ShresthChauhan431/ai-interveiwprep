# 🚀 AI Interview Platform - Complete Setup Guide

This guide will help you set up and run the AI Interview Preparation Platform successfully.

## ✅ Prerequisites Checklist

Before starting, ensure you have:

- [ ] **Java 21+** installed (`java -version`)
- [ ] **Node.js 18+** and npm installed (`node -v`)
- [ ] **MySQL 8.0+** installed and running
- [ ] **Maven 3.8+** installed (`mvn -version`)
- [ ] **Ollama** installed (for AI question generation and feedback)

---

## 📋 Step-by-Step Setup

### 1. Install Ollama (Required for AI Features)

Ollama provides the local LLM for question generation and feedback analysis.

#### macOS:
```bash
brew install ollama
```

#### Linux:
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

#### Windows:
Download from https://ollama.com/download

#### Pull the Llama 3 Model:
```bash
ollama pull llama3
```

#### Start Ollama Server:
```bash
ollama serve
```

**Keep this terminal open** - Ollama must be running for the application to work.

---

### 2. Setup MySQL Database

#### Create Database:
```bash
mysql -u root -p
```

Then run:
```sql
CREATE DATABASE interview_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'interview_user'@'localhost' IDENTIFIED BY 'secure_password_here';
GRANT ALL PRIVILEGES ON interview_platform.* TO 'interview_user'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

Or use the provided script:
```bash
mysql -u root -p < backend/scripts/init-db.sql
```

---

### 3. Configure Backend Environment

#### Update `backend/.env`:

A template `.env` file has been created. Update these critical values:

```bash
# Database credentials
DB_USER=interview_user
DB_PASSWORD=secure_password_here

# API Keys (get from respective providers)
ELEVENLABS_API_KEY=your_elevenlabs_key_from_https://elevenlabs.io/
DID_API_KEY=your_did_key_from_https://www.d-id.com/
ASSEMBLYAI_API_KEY=your_assemblyai_key_from_https://www.assemblyai.com/

# JWT Secret is already generated - DO NOT CHANGE unless rotating
```

#### Get API Keys:

1. **ElevenLabs** (Text-to-Speech):
   - Sign up at https://elevenlabs.io/
   - Go to Profile → API Keys
   - Copy your API key

2. **D-ID** (Avatar Videos):
   - Sign up at https://www.d-id.com/
   - Go to API Keys section
   - Copy your API key

3. **AssemblyAI** (Speech-to-Text):
   - Sign up at https://www.assemblyai.com/
   - Go to Dashboard → API Keys
   - Copy your API key

---

### 4. Create Upload Directory

```bash
mkdir -p backend/uploads
chmod 755 backend/uploads
```

---

### 5. Start Backend

```bash
cd backend
./mvnw clean spring-boot:run
```

**Wait for:** `Started InterviewPlatformApplication in X seconds`

The backend will:
- ✅ Run Flyway migrations (create tables)
- ✅ Seed job roles data
- ✅ Start on port 8081

---

### 6. Start Frontend

Open a **new terminal**:

```bash
cd frontend
npm install
npm start
```

The frontend will open at: http://localhost:3002

---

## 🧪 Testing the Setup

### 1. Check Backend Health:
```bash
curl http://localhost:8081/actuator/health
```

Expected: `{"status":"UP"}`

### 2. Check Ollama:
```bash
curl http://localhost:11434/api/tags
```

Expected: JSON response with `llama3` model listed

### 3. Test Frontend:
- Open http://localhost:3002
- You should see the landing page
- Click "Get Started" → Register

---

## 🎯 Complete Interview Flow Test

### 1. Register/Login:
- Create an account
- Login with credentials

### 2. Upload Resume:
- Go to Dashboard
- Upload a PDF or DOCX resume
- Wait for "Resume uploaded successfully"

### 3. Start Interview:
- Click "Start New Interview"
- Select your uploaded resume
- Choose a job role (e.g., "Software Engineer")
- Select number of questions (5-10 recommended for testing)
- Click "Start Interview"

### 4. Wait for Avatar Generation:
- You'll see a progress bar
- This takes 30-60 seconds per question
- Status will change from "GENERATING_VIDEOS" → "IN_PROGRESS"

### 5. Answer Questions:
- Click "Begin Interview"
- Allow camera/microphone access
- Watch the AI avatar ask the question
- Record your video response (up to 3 minutes)
- Submit and move to next question

### 6. Complete Interview:
- After answering all questions, click "Finish Interview"
- Wait for AI feedback generation (1-2 minutes)

### 7. View Feedback:
- You'll see your overall score
- Strengths, weaknesses, and recommendations
- Detailed analysis of your performance

---

## 🐛 Troubleshooting

### Backend won't start:

**Error: "Unknown database 'interview_platform'"**
```bash
# Solution: Create the database
mysql -u root -p < backend/scripts/init-db.sql
```

**Error: "Connection refused: localhost:11434"**
```bash
# Solution: Start Ollama
ollama serve
```

**Error: "JWT_SECRET not configured"**
```bash
# Solution: Check backend/.env exists and has JWT_SECRET set
cat backend/.env | grep JWT_SECRET
```

### Frontend issues:

**CORS Error:**
```bash
# Solution: Verify CORS_ALLOWED_ORIGINS in backend/.env
echo "CORS_ALLOWED_ORIGINS=http://localhost:3002" >> backend/.env
# Restart backend
```

**Camera not working:**
- Check browser permissions (chrome://settings/content/camera)
- Use HTTPS or localhost (required for getUserMedia)
- Try a different browser

### Interview stuck in "GENERATING_VIDEOS":

**Possible causes:**
1. D-ID API key invalid → Check backend logs
2. D-ID API timeout → Wait 15 minutes for text-only fallback
3. Network issues → Check internet connection

**Check logs:**
```bash
tail -f backend/logs/spring.log
```

### Transcription not working:

**Check AssemblyAI key:**
```bash
curl -H "authorization: YOUR_ASSEMBLYAI_KEY" https://api.assemblyai.com/v2/transcript
```

Expected: `{"error":"Missing transcript_id"}`

---

## 📊 Monitoring

### View Logs:

**Backend:**
```bash
tail -f backend/logs/spring.log
```

**Frontend:**
```bash
# Check browser console (F12)
```

### Check Database:
```bash
mysql -u interview_user -p interview_platform

# View interviews
SELECT id, status, started_at FROM interviews ORDER BY started_at DESC LIMIT 5;

# View questions
SELECT id, interview_id, question_text, avatar_video_url FROM questions LIMIT 5;
```

---

## 🔒 Security Notes

1. **Never commit `.env` files** - They contain secrets
2. **Rotate API keys** if accidentally exposed
3. **Use strong MySQL passwords** in production
4. **Enable HTTPS** for production deployment
5. **Backup database regularly**

---

## 🚀 Production Deployment

See [DEPLOYMENT.md](DEPLOYMENT.md) for production setup instructions including:
- AWS deployment
- Docker containerization
- Environment variable management
- SSL/TLS configuration
- Database backups

---

## 📞 Support

If you encounter issues:

1. Check this guide's troubleshooting section
2. Review backend logs: `backend/logs/spring.log`
3. Check browser console for frontend errors
4. Verify all prerequisites are installed
5. Ensure all services are running (MySQL, Ollama, Backend, Frontend)

---

## ✅ Quick Start Checklist

- [ ] Ollama installed and running (`ollama serve`)
- [ ] Llama3 model pulled (`ollama pull llama3`)
- [ ] MySQL database created
- [ ] `backend/.env` configured with API keys
- [ ] Upload directory created (`backend/uploads`)
- [ ] Backend running on port 8081
- [ ] Frontend running on port 3002
- [ ] Camera/microphone permissions granted in browser

**All set? Visit http://localhost:3002 and start your first interview!** 🎉
