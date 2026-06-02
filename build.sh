#!/bin/bash

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
WHITE='\033[1;37m'
NC='\033[0m'

CheckMavenInstalled() {
    command -v mvn >/dev/null 2>&1
}

InstallMaven() {
    echo -e "${WHITE}[${YELLOW}2/3${WHITE}] Installing Maven...${NC}"

    if [[ -d "/data/data/com.termux/files/usr" ]]; then
        pkg update -y && pkg install -y maven

    elif [[ -f /etc/debian_version ]] || [[ -f /etc/lsb-release ]]; then
        sudo apt update -qq && sudo apt install -y maven

    elif [[ -f /etc/arch-release ]]; then
        sudo pacman -Sy --noconfirm maven

    elif [[ -f /etc/redhat-release ]] || [[ -f /etc/fedora-release ]]; then
        if command -v dnf >/dev/null 2>&1; then
            sudo dnf install -y maven
        else
            sudo yum install -y maven
        fi

    elif [[ -f /etc/alpine-release ]]; then
        sudo apk add --no-cache maven

    elif [[ -f /etc/os-release ]] && grep -q "openSUSE" /etc/os-release; then
        sudo zypper install -y maven

    elif [[ "$(uname)" == "Darwin" ]]; then
        brew install maven

    else
        echo -e "${WHITE}[${RED}ERROR${WHITE}] Unsupported OS. Install Maven manually.${NC}"
        exit 1
    fi
}

echo -e "${WHITE}[${BLUE}1/3${WHITE}] Checking Maven installation...${NC}"

if CheckMavenInstalled; then
    echo -e "${WHITE}[${GREEN}OK${WHITE}] Maven is already installed.${NC}"
else
    InstallMaven

    if ! CheckMavenInstalled; then
        echo -e "${WHITE}[${RED}ERROR${WHITE}] Maven installation failed.${NC}"
        exit 1
    fi

    echo -e "${WHITE}[${GREEN}OK${WHITE}] Maven installed successfully.${NC}"
fi

echo -e "${WHITE}[${BLUE}3/3${WHITE}] Building project with Maven...${NC}"

mvn clean package -q -X

if [[ $? -eq 0 ]]; then
    echo -e "${WHITE}[${GREEN}SUCCESS${WHITE}] Build completed successfully!${NC}"
else
    echo -e "${WHITE}[${RED}ERROR${WHITE}] Build failed!${NC}"
    exit 1
fi
