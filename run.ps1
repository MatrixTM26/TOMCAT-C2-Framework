#Requires -Version 5.1

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$TotalSteps = 3
$Script:CurrentStep = 0

function PrintInfo {
    param([string]$Message)
    $Script:CurrentStep++
    Write-Host "[" -NoNewline -ForegroundColor White
    Write-Host "INFO" -NoNewline -ForegroundColor Blue
    Write-Host "]" -NoNewline -ForegroundColor White
    Write-Host " [step $Script:CurrentStep/$TotalSteps] $Message" -ForegroundColor White
}

function PrintWarn {
    param([string]$Message)
    Write-Host "[" -NoNewline -ForegroundColor White
    Write-Host "WARN" -NoNewline -ForegroundColor Yellow
    Write-Host "]" -NoNewline -ForegroundColor White
    Write-Host " [step $Script:CurrentStep/$TotalSteps] $Message" -ForegroundColor White
}

function PrintError {
    param([string]$Message)
    Write-Host "[" -NoNewline -ForegroundColor White
    Write-Host "ERROR" -NoNewline -ForegroundColor Red
    Write-Host "]" -NoNewline -ForegroundColor White
    Write-Host " [step $Script:CurrentStep/$TotalSteps] $Message" -ForegroundColor White
}

function CheckJava {
    PrintInfo "Checking OpenJDK Java installation"
    $JavaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $JavaCmd) {
        PrintError "Java not found. Please install OpenJDK before running this script."
        exit 1
    }
    $JavaVersion = & java -version 2>&1 | Select-Object -First 1
    PrintWarn "Java found: $JavaVersion"
}

function CopyArtifact {
    PrintInfo "Copying artifact from output directory"
    $SourcePath = "output\tomcat-c2.jar"
    if (-not (Test-Path $SourcePath)) {
        PrintError "Artifact not found: $SourcePath"
        exit 1
    }
    Copy-Item -Path $SourcePath -Destination "." -Force
    PrintWarn "Artifact copied to current directory"
}

function RunApp {
    PrintInfo "Launching application"
    & java -jar tomcat-c2.jar -h
}

function Main {
    CheckJava
    CopyArtifact
    RunApp
}

Main
