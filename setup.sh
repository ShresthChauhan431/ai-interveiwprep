#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# AI Interview Platform - Automated Setup Script
# ═══════════════════════════════════════════════════════════════

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
print_header() {
    echo -e "\n${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}\n"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# ═══════════════════════════════════════════════════════════════
# 1. Check Prerequisites
# ═══════════════════════════════════════════════════════════════

print_header "Checking Prerequisites"

# Check Java
if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -ge 21 ]; then
        print_success "Java $JAVA_VERSION found"
    else
        print_error "Java 21+ required, found Java $JAVA_VERSION"
        exit 1
    fi
else
    print_error "Java not found. Please install Java 21+"
    exit 1
fi

# Check Node.js
if command_exists node; then
    NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
    if [ "$NODE_VERSION" -ge 18 ]; then
        print_success "Node.js $(node -v) found"
    else
        print_error "Node.js 18+ required, found $(node -v)"
        exit 1
    fi
else
    print_error "Node.js not found. Please install Node.js 18+"
    exit 1
fi

# Check Maven
if command_exists mvn; then
    print_success "Maven $(mvn -v | head -n1 | cut -d' ' -f3) found"
else
    print_error "Maven not found. Please install Maven 3.8+"
    exit 1
fi

# Check MySQL
if command_exists mysql; then
    print_success "MySQL found"
else
    print_warning "MySQL not found. You'll need to install it manually."
fi

# Check Ollama
if command_exists ollama; then
    print_success "Ollama found"
else
    print_warning "Ollama not found. Installing..."
    
    # Detect OS and install Ollama
    if [[ "$OSTYPE" == "darwin"* ]]; then
        if command_exists brew; then
            brew install ollama
            print_success "Ollama installed via Homebrew"
        else
            print_error "Homebrew not found. Please install Ollama manually from https://ollama.com"
            exit 1
        fi
    elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
        curl -fsSL https://ollama.com/install.sh | sh
        print_success "Ollama installed"
    else
        print_error "Unsupported OS. Please install Ollama manually from https://ollama.com"
        exit 1
    fi
fi

# ═══════════════════════════════════════════════════════════════
# 2. Setup Ollama
# ═══════════════════════════════════════════════════════════════

print_header "Setting up Ollama"

# Check if Ollama is running
if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
    print_success "Ollama server is running"
else
    print_info "Starting Ollama server..."
    ollama serve > /dev/null 2>&1 &
    sleep 3
    
    if curl -s http://localhost:11434/api/tags >/dev/null 2>&1; then
        print_success "Ollama server started"
    else
        print_error "Failed to start Ollama server"
        exit 1
    fi
fi

# Pull Llama3 model
print_info "Checking for Llama3 model..."
if ollama list | grep -q "llama3"; then
    print_success "Llama3 model already installed"
else
    print_info "Pulling Llama3 model (this may take a few minutes)..."
    ollama pull llama3
    print_success "Llama3 model installed"
fi

# ═══════════════════════════════════════════════════════════════
# 3. Setup Database
# ═══════════════════════════════════════════════════════════════

print_header "Setting up Database"

if command_exists mysql; then
    print_info "Please enter your MySQL root password:"
    read -s MYSQL_ROOT_PASSWORD
    echo
    
    # Test MySQL connection
    if mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1" >/dev/null 2>&1; then
        print_success "MySQL connection successful"
        
        # Check if database exists
        if mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "USE interview_platform" >/dev/null 2>&1; then
            print_warning "Database 'interview_platform' already exists"
        else
            print_info "Creating database..."
            mysql -u root -p"$MYSQL_ROOT_PASSWORD" < backend/scripts/init-db.sql
            print_success "Database created"
        fi
    else
        print_error "MySQL connection failed. Please check your password."
        exit 1
    fi
else
    print_warning "MySQL not found. Please create the database manually."
fi

# ═══════════════════════════════════════════════════════════════
# 4. Configure Backend
# ═══════════════════════════════════════════════════════════════

print_header "Configuring Backend"

# Check if .env exists
if [ -f "backend/.env" ]; then
    print_warning "backend/.env already exists. Backing up to backend/.env.backup"
    cp backend/.env backend/.env.backup
fi

# Update database password in .env
if [ -n "$MYSQL_ROOT_PASSWORD" ]; then
    sed -i.bak "s/DB_PASSWORD=.*/DB_PASSWORD=$MYSQL_ROOT_PASSWORD/" backend/.env
    rm backend/.env.bak
    print_success "Database password configured"
fi

# Create uploads directory
mkdir -p backend/uploads
chmod 755 backend/uploads
print_success "Upload directory created"

# Check API keys
print_info "\nAPI Key Configuration:"
print_warning "You need to manually add API keys to backend/.env:"
echo -e "  1. ELEVENLABS_API_KEY - Get from https://elevenlabs.io/"
echo -e "  2. DID_API_KEY - Get from https://www.d-id.com/"
echo -e "  3. ASSEMBLYAI_API_KEY - Get from https://www.assemblyai.com/"
echo -e "\nEdit backend/.env and replace 'your_*_api_key_here' with actual keys."

read -p "Press Enter when you've added the API keys..."

# ═══════════════════════════════════════════════════════════════
# 5. Build Backend
# ═══════════════════════════════════════════════════════════════

print_header "Building Backend"

cd backend
./mvnw clean compile
if [ $? -eq 0 ]; then
    print_success "Backend compiled successfully"
else
    print_error "Backend compilation failed"
    exit 1
fi
cd ..

# ═══════════════════════════════════════════════════════════════
# 6. Setup Frontend
# ═══════════════════════════════════════════════════════════════

print_header "Setting up Frontend"

cd frontend
if [ ! -d "node_modules" ]; then
    print_info "Installing frontend dependencies..."
    npm install
    print_success "Frontend dependencies installed"
else
    print_success "Frontend dependencies already installed"
fi
cd ..

# ═══════════════════════════════════════════════════════════════
# 7. Final Instructions
# ═══════════════════════════════════════════════════════════════

print_header "Setup Complete!"

echo -e "${GREEN}✓ All prerequisites checked${NC}"
echo -e "${GREEN}✓ Ollama installed and running${NC}"
echo -e "${GREEN}✓ Llama3 model ready${NC}"
echo -e "${GREEN}✓ Database configured${NC}"
echo -e "${GREEN}✓ Backend compiled${NC}"
echo -e "${GREEN}✓ Frontend dependencies installed${NC}"

print_info "\nTo start the application:"
echo -e "\n${BLUE}Terminal 1 - Backend:${NC}"
echo -e "  cd backend"
echo -e "  ./mvnw spring-boot:run"

echo -e "\n${BLUE}Terminal 2 - Frontend:${NC}"
echo -e "  cd frontend"
echo -e "  npm start"

echo -e "\n${BLUE}Terminal 3 - Ollama (if not running):${NC}"
echo -e "  ollama serve"

echo -e "\n${GREEN}Then open: http://localhost:3002${NC}\n"

print_warning "IMPORTANT: Make sure you've added your API keys to backend/.env!"
print_info "See SETUP_GUIDE.md for detailed instructions and troubleshooting."
