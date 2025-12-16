# Lambda Functions Packaging Script (AWS + Tencent Cloud)
# Usage: Execute this script from Signal-Android project root directory

$lambdaDir = "app\src\main\assets\lambda-functions"
Set-Location $lambdaDir

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Lambda Functions Packaging Tool" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

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

# Summary
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Packaging Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Success: $successCount" -ForegroundColor Green
if ($failCount -gt 0) {
    Write-Host "Failed: $failCount" -ForegroundColor Red
}
Write-Host "Output directory: $PWD" -ForegroundColor Gray

# Return to project root directory
Set-Location ..\..\..\..\..
