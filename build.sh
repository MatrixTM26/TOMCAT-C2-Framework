#!/usr/bin/env bash

set -euo pipefail

readonly White='\033[1;37m'
readonly Blue='\033[1;34m'
readonly Yellow='\033[1;33m'
readonly Red='\033[1;31m'
readonly Reset='\033[0m'

TotalSteps=4
CurrentStep=0

PrintInfo() {
    local Message="$1"
    CurrentStep=$((CurrentStep + 1))
    echo -e "${Blue}[INFO]${Reset} ${White}[step ${CurrentStep}/${TotalSteps}] ${Message}${Reset}"
}

PrintWarn() {
    local Message="$1"
    echo -e "${Yellow}[WARN]${Reset} ${White}[step ${CurrentStep}/${TotalSteps}] ${Message}${Reset}"
}

PrintError() {
    local Message="$1"
    echo -e "${Red}[ERROR]${Reset} ${White}[step ${CurrentStep}/${TotalSteps}] ${Message}${Reset}"
}

DetectPackageManager() {
    if command -v pkg &>/dev/null && [ -d "/data/data/com.termux" ]; then
        echo "termux"
    elif command -v pacman &>/dev/null; then
        echo "pacman"
    elif command -v dnf &>/dev/null; then
        echo "dnf"
    elif command -v yum &>/dev/null; then
        echo "yum"
    elif command -v apt-get &>/dev/null; then
        echo "apt"
    elif command -v zypper &>/dev/null; then
        echo "zypper"
    elif command -v apk &>/dev/null; then
        echo "apk"
    elif command -v brew &>/dev/null; then
        echo "brew"
    else
        echo "unknown"
    fi
}

InstallJava() {
    local PackageManager="$1"
    case "$PackageManager" in
        termux)  pkg install -y openjdk-17 ;;
        pacman)  sudo pacman -Sy --noconfirm jdk-openjdk ;;
        dnf)     sudo dnf install -y java-17-openjdk-devel ;;
        yum)     sudo yum install -y java-17-openjdk-devel ;;
        apt)     sudo apt-get install -y openjdk-17-jdk ;;
        zypper)  sudo zypper install -y java-17-openjdk-devel ;;
        apk)     sudo apk add --no-cache openjdk17 ;;
        brew)    brew install openjdk@17 ;;
        *)
            PrintError "Package manager not supported. Install OpenJDK manually."
            exit 1
            ;;
    esac
}

InstallMaven() {
    local PackageManager="$1"
    case "$PackageManager" in
        termux)  pkg install -y maven ;;
        pacman)  sudo pacman -Sy --noconfirm maven ;;
        dnf)     sudo dnf install -y maven ;;
        yum)     sudo yum install -y maven ;;
        apt)     sudo apt-get install -y maven ;;
        zypper)  sudo zypper install -y maven ;;
        apk)     sudo apk add --no-cache maven ;;
        brew)    brew install maven ;;
        *)
            PrintError "Package manager not supported. Install Maven manually."
            exit 1
            ;;
    esac
}

CheckAndInstallJava() {
    PrintInfo "Checking OpenJDK Java installation"
    if command -v java &>/dev/null; then
        local JavaVersion
        JavaVersion=$(java -version 2>&1 | head -n1)
        PrintWarn "Java already installed: ${JavaVersion}"
    else
        PrintWarn "Java not found, detecting package manager..."
        local PackageManager
        PackageManager=$(DetectPackageManager)
        if [ "$PackageManager" = "unknown" ]; then
            PrintError "Cannot detect package manager. Install OpenJDK manually."
            exit 1
        fi
        PrintWarn "Installing OpenJDK via [${PackageManager}]..."
        InstallJava "$PackageManager"
        PrintInfo "Java installed successfully"
    fi
}

CheckAndInstallMaven() {
    PrintInfo "Checking Maven installation"
    if command -v mvn &>/dev/null; then
        local MvnVersion
        MvnVersion=$(mvn -version 2>&1 | head -n1)
        PrintWarn "Maven already installed: ${MvnVersion}"
    else
        PrintWarn "Maven not found, detecting package manager..."
        local PackageManager
        PackageManager=$(DetectPackageManager)
        if [ "$PackageManager" = "unknown" ]; then
            PrintError "Cannot detect package manager. Install Maven manually."
            exit 1
        fi
        PrintWarn "Installing Maven via [${PackageManager}]..."
        InstallMaven "$PackageManager"
        PrintInfo "Maven installed successfully"
    fi
}

RunBuild() {
    PrintInfo "Running Maven build"
    if ! mvn clean package -q -X; then
        PrintError "Maven build failed"
        exit 1
    fi

    if [ ! -f "target/tomcat-c2.jar" ]; then
        PrintError "Build artifact not found: target/tomcat-c2.jar"
        exit 1
    fi

    cp -r target/tomcat-c2.jar .
}

RunApp() {
    PrintInfo "Launching application"
    java -jar tomcat-c2.jar -h
}

Main() {
    CheckAndInstallJava
    CheckAndInstallMaven
    RunBuild
    RunApp
}

Main
