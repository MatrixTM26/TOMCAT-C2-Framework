#!/usr/bin/env bash

set -euo pipefail

readonly White='\033[1;37m'
readonly Blue='\033[1;34m'
readonly Yellow='\033[1;33m'
readonly Red='\033[1;31m'
readonly Reset='\033[0m'

TotalSteps=3
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

CheckJava() {
    PrintInfo "Checking OpenJDK Java installation"
    if ! command -v java &>/dev/null; then
        PrintError "Java not found. Please install OpenJDK before running this script."
        exit 1
    fi
    local JavaVersion
    JavaVersion=$(java -version 2>&1 | head -n1)
    PrintWarn "Java found: ${JavaVersion}"
}

CopyArtifact() {
    PrintInfo "Copying artifact from output directory"
    local SourcePath="output/tomcat-c2.jar"
    if [ ! -f "$SourcePath" ]; then
        PrintError "Artifact not found: ${SourcePath}"
        exit 1
    fi
    cp -r "$SourcePath" .
    PrintWarn "Artifact copied to current directory"
}

RunApp() {
    PrintInfo "Launching application"
    java -jar tomcat-c2.jar -h
}

Main() {
    CheckJava
    CopyArtifact
    RunApp
}

Main
