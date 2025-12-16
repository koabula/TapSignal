# Lambda Functions Packaging Script (AWS + Tencent Cloud)
# Usage: Execute this script from Signal-Android project root directory
#
# Note: For Tencent Cloud Web Functions (tencent-ws-main), this script will use
# WSL or Git Bash to create zip files with proper Unix file permissions.

$lambdaDir = "app\src\main\assets\lambda-functions"
Set-Location $lambdaDir

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Lambda Functions Packaging Tool" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Check for Linux tools availability
$hasWSL = $false
$hasGitBash = $false
$bashPath = $null

if (Get-Command wsl -ErrorAction SilentlyContinue) {
    $hasWSL = $true
    Write-Host "Detected: WSL (Windows Subsystem for Linux)" -ForegroundColor Green
}

if (Test-Path "C:\Program Files\Git\bin\bash.exe") {
    $hasGitBash = $true
    $bashPath = "C:\Program Files\Git\bin\bash.exe"
    Write-Host "Detected: Git Bash" -ForegroundColor Green
} elseif (Test-Path "C:\Program Files (x86)\Git\bin\bash.exe") {
    $hasGitBash = $true
    $bashPath = "C:\Program Files (x86)\Git\bin\bash.exe"
    Write-Host "Detected: Git Bash" -ForegroundColor Green
}

if (-not $hasWSL -and -not $hasGitBash) {
    Write-Host "Warning: Neither WSL nor Git Bash detected." -ForegroundColor Yellow
    Write-Host "tencent-ws-main may not have correct file permissions." -ForegroundColor Yellow
    Write-Host "Install WSL or Git for Windows for proper packaging." -ForegroundColor Yellow
}

# Step 1: Clean old zip files
Write-Host "`n[1/2] Cleaning old zip files..." -ForegroundColor Yellow
$oldZips = Get-ChildItem -Filter "*.zip"
if ($oldZips.Count -gt 0) {
    foreach ($zip in $oldZips) {
        Remove-Item $zip.FullName -Force
        Write-Host "  Deleted: $($zip.Name)" -ForegroundColor Gray
    }
    Write-Host "Cleanup completed, removed $($oldZips.Count) files" -ForegroundColor Green
} else {
    Write-Host "No cleanup needed" -ForegroundColor Green
}

# Step 2: Package Lambda functions
Write-Host "`n[2/2] Packaging Lambda functions..." -ForegroundColor Yellow

$successCount = 0
$failCount = 0

# AWS Lambda functions
$awsFunctions = @(
    "aws-webhook",
    "aws-ws-connect",
    "aws-ws-disconnect",
    "aws-ws-default",
    "aws-f-a"
)

# Tencent Cloud Lambda functions
$tencentFunctions = @(
    "tencent-webhook",
    "tencent-ws-register",
    "tencent-ws-cleanup",
    "tencent-ws-main",
    "tencent-f-a"
)

Write-Host "`n--- AWS Lambda Functions ---" -ForegroundColor Cyan
foreach ($func in $awsFunctions) {
    $jsFile = "$func.js"
    $zipFile = "$func.zip"

    if (Test-Path $jsFile) {
        Write-Host "  Packaging $jsFile..." -ForegroundColor White
        try {
            Copy-Item $jsFile -Destination "index.js" -Force
            Compress-Archive -Path "index.js","package.json" -DestinationPath $zipFile -Force -ErrorAction Stop
            Remove-Item "index.js" -Force
            Write-Host "  Success: $zipFile" -ForegroundColor Green
            $successCount++
        } catch {
            if (Test-Path "index.js") {
                Remove-Item "index.js" -Force
            }
            Write-Host "  Failed: $zipFile - $_" -ForegroundColor Red
            $failCount++
        }
    } else {
        Write-Host "  Missing: $jsFile" -ForegroundColor Red
        $failCount++
    }
}

Write-Host "`n--- Tencent Cloud Lambda Functions ---" -ForegroundColor Cyan
foreach ($func in $tencentFunctions) {
    $jsFile = "$func.js"
    $zipFile = "$func.zip"

    if (Test-Path $jsFile) {
        Write-Host "  Packaging $jsFile..." -ForegroundColor White
        try {
            Copy-Item $jsFile -Destination "index.js" -Force

            # Special handling for tencent-ws-main (Web Function requires scf_bootstrap with exec permission)
            if ($func -eq "tencent-ws-main") {
                if (-not (Test-Path "scf_bootstrap")) {
                    Write-Host "  Error: scf_bootstrap not found" -ForegroundColor Red
                    Remove-Item "index.js" -Force -ErrorAction SilentlyContinue
                    $failCount++
                    continue
                }

                $packaged = $false

                # Try WSL first
                if ($hasWSL -and -not $packaged) {
                    try {
                        Write-Host "  [Web Function] Using WSL to package with Unix permissions..." -ForegroundColor Cyan

                        $currentPath = (Get-Location).Path
                        $wslPath = $currentPath -replace '\\', '/' -replace '^([A-Z]):', { "/mnt/$($_.Groups[1].Value.ToLower())" }

                        $wslCommand = "cd '$wslPath' && chmod 755 scf_bootstrap && zip -q '$zipFile' index.js package.json scf_bootstrap"
                        $result = wsl bash -c $wslCommand 2>&1

                        if ($LASTEXITCODE -eq 0 -and (Test-Path $zipFile)) {
                            Write-Host "  Success: $zipFile (with Unix permissions via WSL)" -ForegroundColor Green
                            $packaged = $true
                            $successCount++
                        }
                    } catch {
                        Write-Host "  WSL packaging failed: $_" -ForegroundColor Yellow
                    }
                }

                # Try Git Bash if WSL failed
                if ($hasGitBash -and -not $packaged) {
                    try {
                        Write-Host "  [Web Function] Using Git Bash to package with Unix permissions..." -ForegroundColor Cyan

                        $currentPath = (Get-Location).Path
                        $gitBashPath = $currentPath -replace '\\', '/' -replace '^([A-Z]):', { "/$($_.Groups[1].Value.ToLower())" }

                        $bashCommand = "cd '$gitBashPath' && chmod 755 scf_bootstrap && zip -q '$zipFile' index.js package.json scf_bootstrap"
                        $result = & $bashPath -c $bashCommand 2>&1

                        if ($LASTEXITCODE -eq 0 -and (Test-Path $zipFile)) {
                            Write-Host "  Success: $zipFile (with Unix permissions via Git Bash)" -ForegroundColor Green
                            $packaged = $true
                            $successCount++
                        }
                    } catch {
                        Write-Host "  Git Bash packaging failed: $_" -ForegroundColor Yellow
                    }
                }

                # Fallback to PowerShell (without proper permissions)
                if (-not $packaged) {
                    Write-Host "  Warning: Using PowerShell fallback (permissions may be incorrect)" -ForegroundColor Yellow
                    try {
                        Compress-Archive -Path "index.js","package.json","scf_bootstrap" -DestinationPath $zipFile -Force -ErrorAction Stop
                        Write-Host "  Success: $zipFile (WARNING: Unix permissions NOT set)" -ForegroundColor Yellow
                        $successCount++
                    } catch {
                        Write-Host "  Failed: $zipFile - $_" -ForegroundColor Red
                        $failCount++
                    }
                }

                Remove-Item "index.js" -Force -ErrorAction SilentlyContinue
            } else {
                # Regular Tencent functions (no special handling needed)
                Compress-Archive -Path "index.js","package.json" -DestinationPath $zipFile -Force -ErrorAction Stop
                Remove-Item "index.js" -Force
                Write-Host "  Success: $zipFile" -ForegroundColor Green
                $successCount++
            }
        } catch {
            if (Test-Path "index.js") {
                Remove-Item "index.js" -Force
            }
            Write-Host "  Failed: $zipFile - $_" -ForegroundColor Red
            $failCount++
        }
    } else {
        Write-Host "  Missing: $jsFile" -ForegroundColor Red
        $failCount++
    }
}

# Summary
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Packaging Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Success: $successCount" -ForegroundColor Green
if ($failCount -gt 0) {
    Write-Host "Failed: $failCount" -ForegroundColor Red
}
Write-Host "Output directory: $PWD" -ForegroundColor Gray

# Important note for tencent-ws-main
if (-not $hasWSL -and -not $hasGitBash) {
    Write-Host "`nIMPORTANT:" -ForegroundColor Yellow
    Write-Host "tencent-ws-main.zip may not have correct Unix permissions." -ForegroundColor Yellow
    Write-Host "The function may fail to start on Tencent Cloud." -ForegroundColor Yellow
    Write-Host "Please install WSL or Git for Windows and re-run this script." -ForegroundColor Yellow
}

# Return to project root directory
Set-Location ..\..\..\..\..
