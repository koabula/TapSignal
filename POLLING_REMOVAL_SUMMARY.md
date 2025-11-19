# 轮询逻辑移除总结

## 变更概述
为了优化 V2 Mode 的性能并消除冗余代码，我们移除了 `TapPollingService` 中的轮询逻辑。V2 Mode 现在完全依赖 Push Notifications (`NotificationDownloadExecutor`) 来接收消息。

## 修改的文件

### 1. `app/src/main/java/org/thoughtcrime/securesms/tap/polling/TapPollingService.kt`
- **变更**: 重写为 "No-Op" (空操作) 实现。
- **详情**:
    - 移除了所有线程池 (`ScheduledThreadPoolExecutor`) 和轮询调度逻辑。
    - `startPolling()` 现在直接返回 `false` 并记录日志。
    - `addPollingTarget()` 返回 `true` 但不执行任何操作（保持 API 兼容性）。
    - `isPollingEnabled()` 永久返回 `false`。
    - 保留了单例结构以避免破坏现有调用者的编译。

### 2. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapModuleInitializer.kt`
- **变更**: 更新了初始化逻辑。
- **详情**:
    - `startPollingService()` 不再尝试启动轮询服务，而是启动 `GroupV2StateSynchronizer`。
    - `retryPollingServiceIfNeeded()` 和 `schedulePollingRetry()` 变为空操作，防止无意义的重试循环。

### 3. `app/src/main/java/org/thoughtcrime/securesms/tap/integration/TapSignalServiceAdapter.kt`
- **变更**: 更新了自动修复逻辑。
- **详情**:
    - `autoFixTapSystemIssues()` 中移除了尝试修复/重启轮询服务的代码。
    - `diagnoseTapSystemState()` 保持不变，它会正确报告轮询服务未启动。

## 验证
- **编译检查**: 所有修改后的文件均保持了原有的公共 API 签名，确保项目可以正常编译。
- **逻辑检查**:
    - `TapMessageProcessor` 检查 `isPollingEnabled()`，返回 `false` 后正确跳过轮询逻辑。
    - `GroupV2StateSynchronizer` 检查 `getPollingState()`，返回 `null` 后正确跳过错误检测，避免误报。

## 后续建议
- 在未来的清理工作中，可以完全删除 `TapPollingService` 类，并从所有调用者中移除相关代码。
- 目前保留该类是为了最小化变更风险和保持代码兼容性。
