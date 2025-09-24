# TAP轮询模块 (Polling)

## 概述

TAP轮询模块负责定期轮询各个Provider（如COS）检查是否有新的加密消息文件需要下载和处理。该模块采用简化的轮询策略，专注于可靠性和资源效率。

## 核心功能

- **文件轮询**：定期检查Provider上的新消息文件
- **任务管理**：每个联系人独立的轮询任务调度
- **错误处理**：完善的错误分类和重试机制  
- **设备自适应**：根据设备性能动态调整轮询策略
- **资源管理**：内存和线程资源的安全管理

## 架构说明

```
TapPollingService (主服务)
├── FilePollingExecutor (文件轮询执行)
├── PollingTaskScheduler (任务调度)
├── PollingErrorClassifier (错误处理)
├── PollingConfigManager (配置管理)
└── DeviceCapabilityProvider (设备检测)
```

## 文件说明

### 核心服务
- **`TapPollingService.kt`** - 轮询服务主类，管理整个轮询生命周期，线程池和任务协调
- **`FilePollingExecutor.kt`** - 文件轮询执行器，负责具体的文件列表获取、下载和消息处理

### 任务管理
- **`PollingTaskInfo.kt`** - 轮询任务信息，封装单个轮询目标的状态、统计和配置
- **`PollingTaskScheduler.kt`** - 任务调度器，管理轮询任务的创建、调度和生命周期

### 配置系统
- **`TapPollingConstants.kt`** - 轮询常量定义，集中管理所有配置参数和阈值
- **`PollingConfigManager.kt`** - 配置管理器，支持运行时配置更新和设备自适应调整
- **`DeviceCapabilityProvider.kt`** - 设备能力检测，提供设备性能评估和线程池配置

### 错误处理
- **`PollingErrorClassifier.kt`** - 错误分类器，根据错误类型提供不同的处理策略和重试机制

### 数据类型
- **`PollingDataTypes.kt`** - 轮询相关的数据类型定义
- **`TapPollingStatus.kt`** - 轮询状态和统计信息相关类型
- **`TransportActivityLevel.kt`** - 传输活跃度级别定义和相关逻辑

## 开发指南

### 添加新Provider支持
1. 在`TapPollingConstants.kt`中添加Provider特定的配置
2. 修改`PollingConfigManager.kt`中的配置逻辑
3. 测试新Provider的轮询行为

### 调整轮询策略
1. 修改`TapPollingConstants.ProviderIntervals`中的基础间隔
2. 调整`ActivityMultipliers`中的活跃度倍数
3. 更新错误处理策略的参数

### 优化性能
1. 检查`DeviceCapabilityProvider.kt`中的设备检测逻辑
2. 调整`TapPollingService.kt`中的线程池配置
3. 优化`FilePollingExecutor.kt`中的文件处理流程

### 调试和监控
- 使用`TapPollingStatus`获取实时状态信息
- 检查`PollingTaskInfo`的统计数据
- 观察错误分类器的处理决策

## 注意事项

- 轮询频率不宜过高，避免API限流
- 错误重试需要指数退避，防止雪崩
- 内存使用需要监控，及时清理过期数据
- 线程池大小根据设备性能动态调整

## 相关模块

- **TransportManager** - Provider管理
- **TransportChannelManager** - 通道管理  
- **TapMessageProcessor** - 消息处理
- **TransportErrorHandler** - 通用错误处理 