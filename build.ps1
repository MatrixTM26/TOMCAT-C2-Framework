#Requires -Version 5.1

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$TotalSteps = 4
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

function DetectWingetOrChoco {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        return "winget"
    } elseif (Get-Command choco -ErrorAction SilentlyContinue) {
        return "choco"
    } else {
        return "unknown"
    }
}

function InstallJavaWindows {
    param([string]$PackageManager)
    switch ($PackageManager) {
        "winget" {
            winget install --id Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements -e
        }
        "choco" {
            choco install openjdk17 -y
        }
        default {
            PrintError "No supported package manager found (winget/choco). Install OpenJDK manually."
            exit 1
        }
    }
}

function InstallMavenWindows {
    param([string]$PackageManager)
    switch ($PackageManager) {
        "winget" {
            winget install --id Apache.Maven --accept-source-agreements --accept-package-agreements -e
        }
        "choco" {
            choco install maven -y
        }
        default {
            PrintError "No supported package manager found (winget/choco). Install Maven manually."
            exit 1
        }
    }
}

function CheckAndInstallJava {
    PrintInfo "Checking OpenJDK Java installation"
    $JavaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($JavaCmd) {
        $JavaVersion = & java -version 2>&1 | Select-Object -First 1
        PrintWarn "Java already installed: $JavaVersion"
    } else {
        PrintWarn "Java not found, detecting package manager..."
        $PackageManager = DetectWingetOrChoco
        PrintWarn "Installing OpenJDK via [$PackageManager]..."
        InstallJavaWindows -PackageManager $PackageManager
        PrintInfo "Java installed successfully"
    }
}

function CheckAndInstallMaven {
    PrintInfo "Checking Maven installation"
    $MvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($MvnCmd) {
        $MvnVersion = & mvn -version 2>&1 | Select-Object -First 1
        PrintWarn "Maven already installed: $MvnVersion"
    } else {
        PrintWarn "Maven not found, detecting package manager..."
        $PackageManager = DetectWingetOrChoco
        PrintWarn "Installing Maven via [$PackageManager]..."
        InstallMavenWindows -PackageManager $PackageManager
        PrintInfo "Maven installed successfully"
    }
}

function RunBuild {
    PrintInfo "Running Maven build"
    & mvn clean package -q -X
    if ($LASTEXITCODE -ne 0) {
        PrintError "Maven build failed"
        exit 1
    }

    $ArtifactPath = "target\tomcat-c2.jar"
    if (-not (Test-Path $ArtifactPath)) {
        PrintError "Build artifact not found: $ArtifactPath"
        exit 1
    }

    Copy-Item -Path $ArtifactPath -Destination "." -Force
}

function RunApp {
    PrintInfo "Launching application"
    & java -jar tomcat-c2.jar -h
}

function Main {
    CheckAndInstallJava
    CheckAndInstallMaven
    RunBuild
    RunApp
}

Main
