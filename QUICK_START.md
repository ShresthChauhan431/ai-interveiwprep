# 🚀 Quick Start - AI Interview Platform

## One-Command Setup

```bash
./setup.sh && ./configure-api-keys.sh && ./start.sh
```

That's it! The application will open at http://localhost:3002

---

## Manual Commands

### Setup (First Time Only)
```bash
./setup.sh
```

### Configure API Keys
```bash
./configure-api-keys.sh
```

### Start Services
```bash
./start.sh
```

### Stop Services
```bash
./stop.sh
```

---

## What You Need

### Required Services
- ✅ MySQL (database)
- ✅ Ollama (AI question generation)

### Required API Keys
- ✅ ElevenLabs - https://elevenlabs.io/
- ✅ D-ID - https://www.d-id.com/
- ✅ AssemblyAI - https://www.assemblyai.com/

---

## Service URLs

| Service | URL | Purpose |
|---------|-----|---------|
| Frontend | http://localhost:3002 | Main application |
| Backend | http://localhost:8081 | REST API |
| Ollama | http://localhost:11434 | AI model |

---

## Logs

```bash
# View backend logs
tail -f logs/backend.log

# View frontend logs
tail -f logs/frontend.log

# View Ollama logs
tail -f logs/ollama.log
```

---

## Troubleshooting

### Backend won't start
```bash
# Check if MySQL is running
mysql -u root -p -e "SELECT 1"

# Check if Ollama is running
curl http://localhost:11434/api/tags

# Check environment variables
cat backend/.env | grep API_KEY
```

### Frontend won't start
```bash
# Reinstall dependencies
cd frontend
rm -rf node_modules package-lock.json
npm install
```

### CORS errors
```bash
# Verify CORS configuration
cat backend/.env | grep CORS
# Should be: CORS_ALLOWED_ORIGINS=http://localhost:3002
```

---

## First Interview Test

1. **Register**: Create an account at http://localhost:3002
2. **Upload Resume**: Go to Dashboard → Upload PDF/DOCX
3. **Start Interview**: Select resume + job role → Start
4. **Wait**: Avatar generation takes 30-60 seconds
5. **Answer**: Record video responses (up to 3 min each)
6. **Complete**: Finish interview → View feedback

---

## Need Help?

- 📖 Detailed Guide: [SETUP_GUIDE.md](SETUP_GUIDE.md)
- 🔧 All Fixes: [FIXES_APPLIED.md](FIXES_APPLIED.md)
- 🚀 Deployment: [DEPLOYMENT.md](DEPLOYMENT.md)

---

## Quick Commands Reference

```bash
# Setup everything
./setup.sh

# Configure API keys
./configure-api-keys.sh

# Start all services
./start.sh

# Stop all services
./stop.sh

# Check backend health
curl http://localhost:8081/actuator/health

# Check Ollama
curl http://localhost:11434/api/tags

# View logs
tail -f logs/backend.log
tail -f logs/frontend.log

# Restart backend only
./stop.sh
cd backend && ./mvnw spring-boot:run

# Restart frontend only
./stop.sh
cd frontend && npm start
```

---

## Environment Variables

Edit `backend/.env` to configure:

```bash
# Database
DB_USER=root
DB_PASSWORD=your_password

# API Keys
ELEVENLABS_API_KEY=your_key
DID_API_KEY=your_key
ASSEMBLYAI_API_KEY=your_key

# JWT (already generated)
JWT_SECRET=<secure_random_string>
```

---

## Success Checklist

- [ ] MySQL running
- [ ] Ollama running with llama3 model
- [ ] backend/.env configured with API keys
- [ ] Backend started (port 8081)
- [ ] Frontend started (port 3002)
- [ ] Browser opens to http://localhost:3002

**All checked? You're ready to go! 🎉**
