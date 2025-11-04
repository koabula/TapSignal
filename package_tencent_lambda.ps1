# 腾讯云 Lambda 函数打包脚本 (Windows PowerShell)
# 使用说明：在 Signal-Android 项目根目录下执行此脚本

# 切换到 Lambda 函数目录
$lambdaDir = "app\src\main\assets\lambda-functions"
Set-Location $lambdaDir

Write-Host "开始打包腾讯云 Lambda 函数..." -ForegroundColor Green

# 打包 tencent-webhook.js (包含package.json)
Write-Host "打包 tencent-webhook.js..." -ForegroundColor Yellow
Compress-Archive -Path "tencent-webhook.js","package.json" -DestinationPath "tencent-webhook.zip" -Force
if ($LASTEXITCODE -eq 0 -or $?) {
    Write-Host "✓ tencent-webhook.zip 打包完成" -ForegroundColor Green
} else {
    Write-Host "✗ tencent-webhook.zip 打包失败" -ForegroundColor Red
}

# 打包 tencent-ws-register.js (包含package.json)
Write-Host "打包 tencent-ws-register.js..." -ForegroundColor Yellow
Compress-Archive -Path "tencent-ws-register.js","package.json" -DestinationPath "tencent-ws-register.zip" -Force
if ($LASTEXITCODE -eq 0 -or $?) {
    Write-Host "✓ tencent-ws-register.zip 打包完成" -ForegroundColor Green
} else {
    Write-Host "✗ tencent-ws-register.zip 打包失败" -ForegroundColor Red
}

# 打包 tencent-ws-cleanup.js (包含package.json)
Write-Host "打包 tencent-ws-cleanup.js..." -ForegroundColor Yellow
Compress-Archive -Path "tencent-ws-cleanup.js","package.json" -DestinationPath "tencent-ws-cleanup.zip" -Force
if ($LASTEXITCODE -eq 0 -or $?) {
    Write-Host "✓ tencent-ws-cleanup.zip 打包完成" -ForegroundColor Green
} else {
    Write-Host "✗ tencent-ws-cleanup.zip 打包失败" -ForegroundColor Red
}

# 打包 tencent-f-a.js (包含package.json)
Write-Host "打包 tencent-f-a.js..." -ForegroundColor Yellow
Compress-Archive -Path "tencent-f-a.js","package.json" -DestinationPath "tencent-f-a.zip" -Force
if ($LASTEXITCODE -eq 0 -or $?) {
    Write-Host "✓ tencent-f-a.zip 打包完成" -ForegroundColor Green
} else {
    Write-Host "✗ tencent-f-a.zip 打包失败" -ForegroundColor Red
}

# 打包 tencent-ws-main.js (包含package.json)
Write-Host "打包 tencent-ws-main.js..." -ForegroundColor Yellow
Compress-Archive -Path "tencent-ws-main.js","package.json" -DestinationPath "tencent-ws-main.zip" -Force
if ($LASTEXITCODE -eq 0 -or $?) {
    Write-Host "✓ tencent-ws-main.zip 打包完成" -ForegroundColor Green
} else {
    Write-Host "✗ tencent-ws-main.zip 打包失败" -ForegroundColor Red
}

Write-Host "`n所有文件打包完成！" -ForegroundColor Green
Write-Host "打包后的文件位于: $PWD" -ForegroundColor Cyan

# 返回项目根目录
Set-Location ..\..\..\..\..

