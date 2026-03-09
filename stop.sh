#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# AI Interview Platform - Stop Script
# ═══════════════════════════════════════════════════════════════

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_info() { echo -e "${BLUE}ℹ $1${NC}"; }

echo -e "${BLUE}Stopping AI Interview Platform services...${NC}\n"

# Stop Backend (Spring Boot on port 8081)
# AUDIT-FIX: Use SIGTERM first for graceful shutdown (Spring Boot flushes DB connections,
# completes in-flight requests, runs @PreDestroy hooks). Only escalate to SIGKILL after timeout.
print_info "Stopping Backend..."
BACKEND_PID=$(lsof -ti:8081)
if [ -n "$BACKEND_PID" ]; then
    kill -15 $BACKEND_PID 2>/dev/null
    # Wait up to 10 seconds for graceful shutdown
    for i in {1..10}; do
        if ! kill -0 $BACKEND_PID 2>/dev/null; then
            break
        fi
        sleep 1
    done
    # SIGKILL if still running after graceful timeout
    if kill -0 $BACKEND_PID 2>/dev/null; then
        kill -9 $BACKEND_PID 2>/dev/null
        print_success "Backend force-stopped (PID: $BACKEND_PID)"
    else
        print_success "Backend stopped gracefully (PID: $BACKEND_PID)"
    fi
else
    print_info "Backend not running"
fi

# Stop Frontend (React on port 3002)
# AUDIT-FIX: Use SIGTERM for graceful shutdown before SIGKILL
print_info "Stopping Frontend..."
FRONTEND_PID=$(lsof -ti:3002)
if [ -n "$FRONTEND_PID" ]; then
    kill -15 $FRONTEND_PID 2>/dev/null
    sleep 2
    if kill -0 $FRONTEND_PID 2>/dev/null; then
        kill -9 $FRONTEND_PID 2>/dev/null
        print_success "Frontend force-stopped (PID: $FRONTEND_PID)"
    else
        print_success "Frontend stopped gracefully (PID: $FRONTEND_PID)"
    fi
else
    print_info "Frontend not running"
fi

# Stop Ollama (optional - you may want to keep it running)
read -p "Stop Ollama server? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_info "Stopping Ollama..."
    pkill -f "ollama serve" 2>/dev/null
    print_success "Ollama stopped"
fi

echo -e "\n${GREEN}All services stopped.${NC}"
echo -e "${BLUE}To start again, run: ./start.sh${NC}\n"
