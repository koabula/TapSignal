# Tap推送服务生命周期管理

## 概述

本模块实现了Tap推送服务的智能生命周期管理，包括：
- Android应用前后台切换
- 网络状态监听和自动重连
- Doze模式处理
- 离线消息补齐

## 组件说明

### 1. TapNotificationService

**综合管理服务**，提供统一的推送服务接口。

**主要功能**：
- 初始化推送服务
- 启动/停止推送服务
- 集成所有生命周期组件
- 处理离线消息

**使用示例**：

```kotlin
val service = TapNotificationService.getInstance(context)

// 初始化
service.initialize()

// 启动服务
service.start { notification ->
    // 处理收到的推送通知
    handleNotification(notification)
}

// 检查状态
if (service.isConnected()) {
    Log.d(TAG, "推送服务已连接")
}

// 手动触发离线消息处理
service.processOfflineMessages()

// 停止服务
service.stop()
```

### 2. TapLifecycleIntegrator

**生命周期集成器**，集成Android应用生命周期和Tap推送服务。

**主要功能**：
- 监听应用前后台切换（通过`AppForegroundObserver`）
- 自动管理WebSocket连接
- 网络变化处理
- 离线消息处理

**工作流程**：
1. 应用进入前台 → 建立WebSocket连接 → 处理离线消息
2. 应用进入后台 → 延迟5分钟后断开连接
3. 网络可用 → 尝试重连
4. 网络丢失 → 取消重连任务

### 3. NotificationOfflineHandler

**离线消息处理器**，处理离线期间未接收的消息。

**主要功能**：
- 从所有活跃通道拉取离线消息
- 复用polling模块的下载和处理逻辑
- 批量处理（最多50条/次）
- 防止频繁处理（最小间隔1分钟）

**处理时机**：
- 应用进入前台时
- 退出Doze模式时
- Doze模式下定期检查（30分钟）
- 手动触发

### 4. NotificationNetworkMonitor

**网络监听器**，监听网络连接状态变化。

**主要功能**：
- 实时监听网络状态
- 支持Android N及以上的`NetworkCallback`
- 通知所有监听器网络变化

**工作原理**：
```
网络可用 → 触发重连逻辑
网络丢失 → 取消重连任务
```

### 5. TapDozeHandler

**Doze模式处理器**，处理Android省电模式。

**主要功能**：
- 监听Doze模式状态变化
- 在Doze模式下定期检查离线消息（30分钟）
- 使用`setExactAndAllowWhileIdle`确保在Doze模式下执行

**Doze模式处理流程**：
```
进入Doze → 调度定期检查（30分钟）
定期检查 → 处理离线消息 → 调度下次检查
退出Doze → 取消定期检查 → 处理离线消息
```

## 集成指南

### 步骤1：在Application初始化时注册

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 注册AppForegroundObserver
        AppForegroundObserver.begin()
    }
}
```

### 步骤2：在Tap模块初始化时启动推送服务

```kotlin
// 在TapModuleInitializer中
fun initializeNotificationService() {
    val service = TapNotificationService.getInstance(context)
    
    lifecycleScope.launch {
        // 初始化
        if (service.initialize()) {
            // 启动服务
            service.start { notification ->
                handlePushNotification(notification)
            }
        }
    }
}

private fun handlePushNotification(notification: NotificationMessage) {
    when (notification.type) {
        NotificationMessage.TYPE_NEW_MESSAGE -> {
            // 处理新消息通知
            val senderId = notification.senderId
            // 触发消息下载（polling模块的下载逻辑会被调用）
        }
        NotificationMessage.TYPE_HEARTBEAT -> {
            // 心跳消息
        }
    }
}
```

### 步骤3：在用户登出时停止服务

```kotlin
fun cleanupNotificationService() {
    TapNotificationService.getInstance(context).stop()
}
```

## 权限要求

在`AndroidManifest.xml`中添加必要权限：

```xml
<!-- 网络状态 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 唤醒设备（用于Doze模式） -->
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- 调度精确闹钟（用于Doze模式定期检查） -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

## 配置说明

### 前后台切换时间

- **后台断开延迟**：5分钟（`BACKGROUND_DISCONNECT_DELAY_MS`）
- **重连基础延迟**：1秒（`RECONNECT_BASE_DELAY_MS`）
- **重连最大延迟**：60秒（`RECONNECT_MAX_DELAY_MS`）
- **最大重连次数**：10次（`MAX_RECONNECT_ATTEMPTS`）

### Doze模式配置

- **定期检查间隔**：30分钟（`DOZE_CHECK_INTERVAL_MS`）

### 离线消息处理

- **处理最小间隔**：1分钟
- **单次批量大小**：50条消息（`MAX_BATCH_SIZE`）
- **处理超时**：30秒（`OFFLINE_PROCESS_TIMEOUT_MS`）

## 状态监控

### 获取服务状态

```kotlin
val status = TapNotificationService.getInstance(context).getServiceStatus()

Log.d(TAG, """
    初始化: ${status.initialized}
    启动: ${status.started}
    连接: ${status.connected}
    Doze模式: ${status.inDozeMode}
    处理离线消息: ${status.processingOffline}
    上次处理时间: ${status.lastOfflineProcessTime}
    健康状态: ${status.isHealthy()}
""".trimIndent())
```

## 架构设计

```
TapNotificationService (综合管理)
    │
    ├── TapLifecycleIntegrator (生命周期集成)
    │   ├── AppForegroundObserver (前后台监听)
    │   ├── NotificationConnectionManager (连接管理)
    │   ├── NotificationNetworkMonitor (网络监听)
    │   └── NotificationOfflineHandler (离线处理)
    │
    ├── TapDozeHandler (Doze模式处理)
    │   └── AlarmManager (定期检查)
    │
    └── NotificationManager (推送管理)
        └── NotificationProvider (推送提供商)
```

## 最佳实践

### 1. 连接管理

- 前台应用保持连接
- 后台延迟5分钟后断开
- 网络恢复时自动重连

### 2. 离线消息处理

- 进入前台时自动处理
- Doze模式下定期处理
- 避免频繁处理（最小间隔1分钟）

### 3. 电量优化

- 后台自动断开连接
- Doze模式下降低检查频率
- 使用`setExactAndAllowWhileIdle`而非持久连接

### 4. 错误处理

- 连接失败自动重试（指数退避）
- 最大重试次数限制
- 异常日志记录

## 故障排查

### 问题1：推送消息收不到

**检查步骤**：
1. 确认服务已初始化：`service.isInitialized()`
2. 确认服务已启动：`service.isStarted()`
3. 确认WebSocket已连接：`service.isConnected()`
4. 检查网络状态
5. 查看日志是否有连接错误

### 问题2：离线消息未补齐

**检查步骤**：
1. 确认离线处理器正在运行：`status.processingOffline`
2. 检查上次处理时间：`status.lastOfflineProcessTime`
3. 手动触发处理：`service.processOfflineMessages()`
4. 查看日志中的离线消息处理记录

### 问题3：Doze模式下无法接收

**说明**：
Doze模式下WebSocket连接会被断开，这是正常行为。系统会每30分钟检查一次离线消息。

**解决方案**：
- 使用Firebase Cloud Messaging等系统级推送服务
- 或接受30分钟的延迟（符合省电设计）

## 版本历史

- **v2.0** (2025-11-02): Phase 6实现
  - 生命周期集成
  - 离线消息处理
  - Doze模式支持
  - 网络监听

## 相关文档

- [PLAN_Notice.md](../../../../../../../../../PLAN_Notice.md) - 推送机制升级计划
- [NotificationProvider接口](../NotificationProvider.kt) - 推送服务提供商接口
- [WebhookProtocol](../WebhookProtocol.kt) - Webhook协议定义

