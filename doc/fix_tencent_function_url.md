# 腾讯云函数URL部署问题修复说明

## 修复日期
2025-12-12

## 问题概述

腾讯云使用函数URL替代已停服的API Gateway时，遇到两个关键问题导致WebSocket连接和触发器调用失败。

## 问题详情

### 问题1: Invoke API参数错误

**错误日志:**
```
java.lang.IllegalStateException: API错误[UnknownParameter]: The parameter `Event` is not recognized.
```

**根本原因:**
- 代码使用了错误的API参数名 `Event`
- 腾讯云SCF Invoke API使用 `ClientContext` 参数传递事件数据
- AWS Lambda使用 `Payload`，两者不同

**影响范围:**
- 触发器云函数无法被调用
- 消息分发失败

### 问题2: scf_bootstrap文件权限缺失

**错误日志:**
```
Response Body: {"errorMessage":"[./scf_bootstrap] no such file or directory","statusCode":443}
java.net.ProtocolException: Expected HTTP 101 response but was '443 status code 443'
```

**根本原因:**
- 腾讯云Web函数需要 `scf_bootstrap` 启动脚本
- 必须具有Unix可执行权限 (755)
- Windows PowerShell的 Compress-Archive 不保留Unix权限
- 导致云函数无法启动，WebSocket握手失败

**影响范围:**
- WebSocket主函数无法启动
- 客户端无法建立WebSocket连接
- 实时消息推送功能失效

## 修复方案

### 修复1: 更正Invoke API参数名

**修改文件:**
`app/src/main/java/org/thoughtcrime/securesms/tap/provider/cos/notification/TapLambdaDispatcher.kt`

**修改内容:**
```kotlin
// 第225行附近，TencentTapLambdaDispatcher.dispatch方法
val params = JSONObject().apply {
    put("FunctionName", functionName)
    put("InvocationType", "RequestResponse")
    put("Namespace", "default")
    put("ClientContext", payload.toString())  // 从 Event 改为 ClientContext
}
```

### 修复2: 部署时动态修复zip权限

**修改文件:**
`app/src/main/java/org/thoughtcrime/securesms/tap/notification/provider/tencent/TencentApiGatewayDeployer.kt`

**添加方法:**
```kotlin
private fun fixScfBootstrapPermissions(originalZipData: ByteArray): ByteArray {
    return try {
        Log.d(TAG, "Fixing scf_bootstrap permissions in zip file")
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            ZipInputStream(ByteArrayInputStream(originalZipData)).use { zis ->
                var entry = zis.readNextEntry()
                while (entry != null) {
                    val newEntry = ZipEntry(entry.name)
                    
                    if (entry.name == "scf_bootstrap") {
                        // 设置Unix权限为755 (rwxr-xr-x)
                        newEntry.externalAttributes = 0b111101101 shl 16
                        Log.d(TAG, "Set executable permission (755) for scf_bootstrap")
                    } else {
                        newEntry.externalAttributes = entry.externalAttributes
                    }
                    
                    newEntry.time = entry.time
                    newEntry.comment = entry.comment
                    
                    zos.putNextEntry(newEntry)
                    zis.copyTo(zos)
                    zos.closeEntry()
                    
                    entry = zis.readNextEntry()
                }
            }
        }
        Log.d(TAG, "Successfully fixed scf_bootstrap permissions")
        baos.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fix scf_bootstrap permissions, using original zip", e)
        originalZipData
    }
}
```

**调用位置:**
在 `createCloudFunction` 方法中，对Web函数的zip数据进行处理：
```kotlin
val processedZipData = if (isWebFunction) {
    fixScfBootstrapPermissions(zipData)
} else {
    zipData
}
```

**添加导入:**
```kotlin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
```

## 技术说明

### Unix文件权限在Zip中的存储

- Unix权限存储在ZipEntry的 `externalAttributes` 字段
- 格式: `(permissions << 16) | file_type`
- 755权限 = `rwxr-xr-x` = `111 101 101` (二进制) = 0755 (八进制)
- 需要左移16位存储

### 腾讯云Web函数启动流程

1. 云函数实例启动
2. 查找根目录的 scf_bootstrap 文件
3. 检查可执行权限
4. 执行 scf_bootstrap (启动Web服务)
5. Web服务监听9000端口
6. 函数URL将请求转发到9000端口

如果第3步失败(无执行权限)，返回443错误码(用户代码错误)。

### scf_bootstrap内容

```bash
#!/bin/bash
export PORT=9000
node index.js
```

该脚本设置端口并启动Node.js Web服务。

## 验证步骤

### 1. 编译项目
```bash
./gradlew assembleDebug
```

### 2. 重新部署
- 在Signal应用中删除现有的腾讯云部署
- 重新配置TAP并部署
- 观察部署日志

### 3. 验证问题1修复
检查日志中不再出现:
```
API错误[UnknownParameter]: The parameter `Event` is not recognized
```

### 4. 验证问题2修复
检查日志中:
- 出现 "Fixing scf_bootstrap permissions in zip file"
- 出现 "Set executable permission (755) for scf_bootstrap"
- 不再出现 "no such file or directory"
- WebSocket连接成功

### 5. 功能测试
- 发送单聊消息，验证触发器调用成功
- 建立WebSocket连接，验证实时推送工作
- 发送离线消息，验证上线后能同步

## 重新打包Lambda函数

如果需要重新打包函数zip文件:

```powershell
cd Signal-Android
powershell.exe -File doc/package_lambda_functions.ps1
```

注意: scf_bootstrap的Unix权限会在部署时自动设置，打包脚本无需处理权限问题。

## 相关文件

- `TapLambdaDispatcher.kt` - 触发器调用逻辑
- `TencentApiGatewayDeployer.kt` - 腾讯云函数部署
- `package_lambda_functions.ps1` - Lambda函数打包脚本
- `tencent-ws-main.js` - WebSocket主函数
- `scf_bootstrap` - Web函数启动脚本

## 参考文档

- 腾讯云SCF Invoke API: https://cloud.tencent.com/document/product/583/58400
- 腾讯云Web函数: https://cloud.tencent.com/document/product/583/96099
- 腾讯云函数URL: https://cloud.tencent.com/document/product/583/107631

## 注意事项

1. 此修复仅针对腾讯云部署，AWS部署不受影响
2. 权限修复逻辑在每次部署时自动执行
3. 如果部署失败，检查日志中的权限设置信息
4. 旧的部署可能需要删除后重新部署以应用修复