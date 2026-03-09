#!/bin/bash

# ═══════════════════════════════════════════════════════════════
# API Keys Configuration Helper
# ═══════════════════════════════════════════════════════════════

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║        API Keys Configuration Helper                          ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}\n"

# Check if .env exists
if [ ! -f "backend/.env" ]; then
    echo -e "${YELLOW}backend/.env not found. Creating from template...${NC}"
    cp backend/.env.example backend/.env
fi

echo -e "${BLUE}This script will help you configure your API keys.${NC}\n"

# ElevenLabs
echo -e "${YELLOW}═══ ElevenLabs (Text-to-Speech) ═══${NC}"
echo "Get your API key from: https://elevenlabs.io/"
echo "1. Sign up / Log in"
echo "2. Go to Profile → API Keys"
echo "3. Copy your API key"
echo ""
read -p "Enter your ElevenLabs API key: " ELEVENLABS_KEY

if [ -n "$ELEVENLABS_KEY" ]; then
    sed -i.bak "s/ELEVENLABS_API_KEY=.*/ELEVENLABS_API_KEY=$ELEVENLABS_KEY/" backend/.env
    echo -e "${GREEN}✓ ElevenLabs API key configured${NC}\n"
else
    echo -e "${YELLOW}⚠ Skipped ElevenLabs configuration${NC}\n"
fi

# D-ID
echo -e "${YELLOW}═══ D-ID (Avatar Video Generation) ═══${NC}"
echo "Get your API key from: https://www.d-id.com/"
echo "1. Sign up / Log in"
echo "2. Go to API Keys section"
echo "3. Copy your API key"
echo ""
read -p "Enter your D-ID API key: " DID_KEY

if [ -n "$DID_KEY" ]; then
    sed -i.bak "s/DID_API_KEY=.*/DID_API_KEY=$DID_KEY/" backend/.env
    echo -e "${GREEN}✓ D-ID API key configured${NC}\n"
else
    echo -e "${YELLOW}⚠ Skipped D-ID configuration${NC}\n"
fi

# AssemblyAI
echo -e "${YELLOW}═══ AssemblyAI (Speech-to-Text) ═══${NC}"
echo "Get your API key from: https://www.assemblyai.com/"
echo "1. Sign up / Log in"
echo "2. Go to Dashboard → API Keys"
echo "3. Copy your API key"
echo ""
read -p "Enter your AssemblyAI API key: " ASSEMBLYAI_KEY

if [ -n "$ASSEMBLYAI_KEY" ]; then
    sed -i.bak "s/ASSEMBLYAI_API_KEY=.*/ASSEMBLYAI_API_KEY=$ASSEMBLYAI_KEY/" backend/.env
    echo -e "${GREEN}✓ AssemblyAI API key configured${NC}\n"
else
    echo -e "${YELLOW}⚠ Skipped AssemblyAI configuration${NC}\n"
fi

# MySQL Password
echo -e "${YELLOW}═══ MySQL Database ═══${NC}"
read -p "Enter your MySQL password (or press Enter to skip): " MYSQL_PASSWORD

if [ -n "$MYSQL_PASSWORD" ]; then
    sed -i.bak "s/DB_PASSWORD=.*/DB_PASSWORD=$MYSQL_PASSWORD/" backend/.env
    echo -e "${GREEN}✓ MySQL password configured${NC}\n"
else
    echo -e "${YELLOW}⚠ Skipped MySQL configuration${NC}\n"
fi

# Clean up backup files
rm -f backend/.env.bak

echo -e "${GREEN}"
cat << "EOF"
╔═══════════════════════════════════════════════════════════════╗
║                                                               ║
║        ✓ Configuration Complete!                              ║
║                                                               ║
╚═══════════════════════════════════════════════════════════════╝
EOF
echo -e "${NC}\n"

echo -e "${BLUE}Your API keys have been saved to backend/.env${NC}"
echo -e "${YELLOW}IMPORTANT: Never commit this file to version control!${NC}\n"

echo -e "${BLUE}Next steps:${NC}"
echo "1. Verify your configuration: cat backend/.env"
echo "2. Start the application: ./start.sh"
echo ""
