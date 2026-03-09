#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# AI Interview Platform - Quick Start Script
# ═══════════════════════════════════════════════════════════════
# This script starts all required services in the background
# ═══════════════════════════════════════════════════════════════

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_info() { echo -e "${BLUE}ℹ $1${NC}"; }

echo -e "${BLUE}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║        AI Interview Preparation Platform                      ║
║        Starting Services...                                   ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}"

# Check if setup has been run
if [ ! -f "backend/.env" ]; then
    print_error "Setup not complete. Please run ./setup.sh first"
    exit 1
fi

# AUDIT-FIX: Source backend/.env so environment variables (JWT_SECRET, CORS_ALLOWED_ORIGINS,
# DB_USER, DB_PASSWORD, etc.) are exported into this shell and inherited by child processes.
# Spring Boot does NOT auto-load .env files — without this, the backend will fail to start
# because JWT_SECRET and CORS_ALLOWED_ORIGINS have no defaults after the security audit.
print_info "Loading environment variables from backend/.env..."
set -a  # auto-export all variables
source backend/.env
set +a
print_success "Environment variables loaded"

# AUDIT-FIX: Validate mandatory env vars that have no defaults after the security audit
MISSING_VARS=""
if [ -z "$JWT_SECRET" ]; then
    MISSING_VARS="$MISSING_VARS JWT_SECRET"
fi
if [ -z "$CORS_ALLOWED_ORIGINS" ]; then
    MISSING_VARS="$MISSING_VARS CORS_ALLOWED_ORIGINS"
fi
if [ -z "$DB_USER" ]; then
    MISSING_VARS="$MISSING_VARS DB_USER"
fi
if [ -z "$DB_PASSWORD" ]; then
    MISSING_VARS="$MISSING_VARS DB_PASSWORD"
fi
if [ -n "$MISSING_VARS" ]; then
    print_error "Missing required environment variables in backend/.env:$MISSING_VARS"
    print_info "Edit backend/.env and set these values, then re-run ./start.sh"
    exit 1
fi
print_success "Required environment variables validated"

# Create logs directory
mkdir -p logs

# ═══════════════════════════════════════════════════════════════
# 1. Start Ollama
# ═══════════════════════════════════════════════════════════════

print_info "Checking Ollama..."
if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
    print_success "Ollama is already running"
else
    print_info "Starting Ollama server..."
    nohup ollama serve > logs/ollama.log 2>&1 &
    sleep 3

    if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
        print_success "Ollama started"
    else
        print_error "Failed to start Ollama"
        exit 1
    fi
fi

# ═══════════════════════════════════════════════════════════════
# 2. Start Backend
# ═══════════════════════════════════════════════════════════════

print_info "Starting Backend (Spring Boot)..."

# Check if backend is already running
if curl -s http://localhost:8081/actuator/health >/dev/null 2>&1; then
    print_success "Backend is already running"
else
    cd backend
    # AUDIT-FIX: env vars are already exported via 'source backend/.env' above,
    # so the Spring Boot process inherits JWT_SECRET, CORS_ALLOWED_ORIGINS, etc.
    nohup ./mvnw spring-boot:run > ../logs/backend.log 2>&1 &
    BACKEND_PID=$!
    cd ..

    print_info "Waiting for backend to start (this may take 30-60 seconds)..."

    # Wait up to 120 seconds for backend to start
    for i in {1..120}; do
        if curl -s http://localhost:8081/actuator/health >/dev/null 2>&1; then
            print_success "Backend started (PID: $BACKEND_PID)"
            break
        fi

        if [ $i -eq 120 ]; then
            print_error "Backend failed to start. Check logs/backend.log"
            exit 1
        fi

        sleep 1
    done
fi

# ═══════════════════════════════════════════════════════════════
# 3. Start Frontend
# ═══════════════════════════════════════════════════════════════

print_info "Starting Frontend (React)..."

# Check if frontend is already running
if curl -s http://localhost:3002 >/dev/null 2>&1; then
    print_success "Frontend is already running"
else
    cd frontend
    nohup npm start > ../logs/frontend.log 2>&1 &
    FRONTEND_PID=$!
    cd ..

    print_info "Waiting for frontend to start..."

    # Wait up to 60 seconds for frontend to start
    for i in {1..60}; do
        if curl -s http://localhost:3002 >/dev/null 2>&1; then
            print_success "Frontend started (PID: $FRONTEND_PID)"
            break
        fi

        if [ $i -eq 60 ]; then
            print_error "Frontend failed to start. Check logs/frontend.log"
            exit 1
        fi

        sleep 1
    done
fi

# ═══════════════════════════════════════════════════════════════
# Success!
# ═══════════════════════════════════════════════════════════════

echo -e "\n${GREEN}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║        ✓ All Services Started Successfully!                   ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}"

echo -e "${BLUE}Services Running:${NC}"
echo -e "  • Ollama:   http://localhost:11434"
echo -e "  • Backend:  http://localhost:8081"
echo -e "  • Frontend: ${GREEN}http://localhost:3002${NC}"

echo -e "\n${BLUE}Logs:${NC}"
echo -e "  • Ollama:   logs/ollama.log"
echo -e "  • Backend:  logs/backend.log"
echo -e "  • Frontend: logs/frontend.log"

echo -e "\n${YELLOW}To view logs in real-time:${NC}"
echo -e "  tail -f logs/backend.log"
echo -e "  tail -f logs/frontend.log"

echo -e "\n${YELLOW}To stop all services:${NC}"
echo -e "  ./stop.sh"

echo -e "\n${GREEN}Opening browser...${NC}\n"

# Open browser (works on macOS and Linux)
if [[ "$OSTYPE" == "darwin"* ]]; then
    open http://localhost:3002
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    xdg-open http://localhost:3002 2>/dev/null || echo "Please open http://localhost:3002 in your browser"
fi
