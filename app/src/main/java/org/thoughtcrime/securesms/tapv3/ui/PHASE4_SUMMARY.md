# Tap v3 UI 层实现

## 概述

Phase 4 完成了 Tap v3 的用户界面实现,包括配置界面、握手对话框和状态指示器。这些组件为用户提供了直观的方式来配置和使用 Tap v3 功能。

## 实现的组件

### 1. TapV3ConfigFragment - 配置界面

负责 Tap v3 的所有配置管理,包括 IPFS Gateway 和 UnifiedPush 设置。

#### 功能特性

```kotlin
class TapV3ConfigFragment : DSLSettingsFragment
```

- **IPFS Gateway 配置**
  - Pinata API Key/Secret
  - Web3.Storage Token
  - 支持多个 Gateway 同时配置

- **UnifiedPush 配置**
  - 显示推送注册状态
  - 显示当前端点
  - 注册/注销功能

- **配置测试**
  - 上传测试数据到 IPFS
  - 下载并验证数据
  - 显示测试结果

- **配置管理**
  - 保存配置到 SignalStore
  - 清除配置
  - 确认对话框

#### UI 结构

```
配置界面
├── IPFS Gateway 配置
│   ├── Pinata API Key (可选)
│   ├── Pinata API Secret (可选)
│   └── Web3.Storage Token (可选)
├── UnifiedPush 配置
│   ├── 推送状态显示
│   ├── 端点显示
│   └── 注册/注销按钮
└── 操作
    ├── 测试配置
    ├── 保存配置
    └── 清除配置
```

### 2. TapV3ConfigViewModel - 配置业务逻辑

管理配置界面的状态和业务逻辑。

#### 状态管理

```kotlin
data class TapV3ConfigState(
    val isLoading: Boolean,
    val pinataApiKey: String,
    val pinataApiSecret: String,
    val web3StorageToken: String,
    val isPushRegistered: Boolean,
    val myPushEndpoint: String?,
    val testState: TestState,
    val testError: String?
)
```

#### 核心功能

- **配置加载**: 从 SignalStore 加载已保存的配置
- **UnifiedPush 管理**: 注册/注销推送服务
- **配置测试**: 验证 IPFS Gateway 连接性
- **配置持久化**: 保存到 SignalStore 和 TapV3Manager

#### 测试流程

```
1. 生成测试数据 ("Tap v3 test")
2. 上传到配置的 Gateway
3. 获取 CID
4. 从 IPFS 下载数据
5. 验证数据一致性
6. 更新测试状态
```

### 3. TapV3HandshakeDialog - 握手对话框

实现与联系人建立 Tap v3 通道的 UI 流程。

#### 握手状态

```kotlin
enum class HandshakeStatus {
    IDLE,              // 准备就绪
    INITIATING,        // 发起中
    WAITING_RESPONSE,  // 等待响应
    COMPLETING,        // 完成中
    COMPLETED,         // 已完成
    FAILED             // 失败
}
```

#### UI 元素

- 标题和联系人名称
- 进度指示器
- 状态文本
- 错误信息容器
- 通道信息容器 (显示对方端点)
- 操作按钮 (发起/重试/取消)

#### 交互流程

```
1. 用户点击 "发起握手"
2. 显示进度条和 "发起中" 状态
3. 调用 HandshakeManager.initiateHandshake()
4. 轮询握手状态 (最多 60 秒)
5. 显示握手结果:
   - 成功: 显示对方端点
   - 失败: 显示错误信息和重试按钮
```

### 4. TapV3HandshakeViewModel - 握手业务逻辑

管理握手对话框的状态和与协议层的交互。

#### 状态轮询

```kotlin
private fun pollHandshakeStatus(recipientId: String) {
    // 每秒检查一次握手状态
    // 最多等待 60 秒
    // 根据握手管理器的状态更新 UI
}
```

#### 错误处理

- 握手发起失败: 立即显示错误
- 超时: 60 秒无响应视为失败
- 对方拒绝: 显示拒绝原因

### 5. TapV3StatusIndicator - 状态指示器

在对话列表中显示 v3 通道状态的小标识。

#### 显示逻辑

```kotlin
fun updateStatus(recipient: Recipient) {
    // 1. 检查是否为群组 (不支持)
    // 2. 获取 recipient ACI
    // 3. 查询 v3 通道状态
    // 4. 根据状态显示或隐藏指示器
}
```

#### 状态样式

| 通道状态 | 显示文本 | 颜色 |
|---------|---------|------|
| ACTIVE | v3 | 绿色 (Primary) |
| PENDING | v3? | 灰色 (Secondary) |
| FAILED | v3! | 红色 (Error) |

#### 实现特点

- 异步状态查询 (避免主线程阻塞)
- 自动取消重复更新
- Coroutine 作用域管理
- 自动清理资源

## 布局文件

### tap_v3_handshake_dialog.xml

```xml
<ConstraintLayout>
  ├── Title
  ├── Recipient Name
  ├── Progress Bar
  ├── Status Text
  ├── Error Container
  │   └── Error Text
  ├── Channel Info Container
  │   ├── Peer Endpoint Label
  │   └── Peer Endpoint Text
  ├── Initiate Handshake Button
  └── Cancel Button
</ConstraintLayout>
```

### tap_v3_status_indicator.xml

```xml
<LinearLayout>
  └── Indicator TextView
      └── Background: tap_v3_indicator_background
```

## 字符串资源

创建了独立的字符串资源文件 `strings_tapv3.xml`:

- 配置界面所有文本 (45+ 字符串)
- 握手对话框文本 (13 字符串)
- 设置入口文本 (2 字符串)

## 集成指南

### 在设置中添加 Tap v3 入口

```kotlin
// 在 AppSettingsFragment 中:
clickPref(
    title = DSLSettingsText.from(R.string.preferences__tap_v3),
    summary = DSLSettingsText.from(R.string.preferences__tap_v3_summary),
    icon = DSLSettingsIcon.from(R.drawable.symbol_settings_24),
    onClick = {
        findNavController().navigate(R.id.action_to_tapV3Config)
    }
)
```

### 在联系人详情页添加握手按钮

```kotlin
// 在 RecipientBottomSheetDialogFragment 中:
if (recipient.isRegistered && !recipient.isGroup) {
    clickPref(
        title = DSLSettingsText.from("Initiate Tap v3 Handshake"),
        onClick = {
            TapV3HandshakeDialog.create(recipient.id).show(
                parentFragmentManager,
                "TapV3Handshake"
            )
        }
    )
}
```

### 在对话列表中显示状态指示器

```xml
<!-- 在 conversation_list_item.xml 中: -->
<org.thoughtcrime.securesms.tapv3.ui.TapV3StatusIndicator
    android:id="@+id/tap_v3_indicator"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content" />
```

```kotlin
// 在 ConversationListItemViewHolder 中:
tapV3Indicator.updateStatus(recipient)
```

## 样式指南

### 配置界面样式

- 使用 Signal DSL Settings Framework
- 遵循 Material Design 3 规范
- 与现有设置界面风格一致

### 握手对话框样式

- BottomSheetDialogFragment 风格
- Material Button 样式
- 圆角背景 (4dp)

### 状态指示器样式

- 小标签样式 (10sp)
- 圆角边框 (4dp)
- 最小内边距 (6dp 横向, 2dp 纵向)

## 用户流程

### 首次配置流程

```
1. 进入 Signal 设置
2. 点击 "Tap v3"
3. 配置至少一个 IPFS Gateway:
   - Pinata (需要 API Key + Secret)
   - Web3.Storage (需要 Token)
4. 注册 UnifiedPush (需要安装 Distributor)
5. 测试配置
6. 保存配置
```

### 建立 v3 通道流程

```
1. 打开联系人详情
2. 点击 "Initiate Tap v3 Handshake"
3. 等待握手完成 (自动)
4. 查看对方端点信息
5. 关闭对话框
6. 对话列表显示 "v3" 标识
```

### 发送 v3 消息流程

```
1. 输入消息
2. 发送
3. 系统自动判断:
   - 短消息 → 内联传输
   - 长消息/附件 → IPFS 传输
4. 对方通过 UnifiedPush 接收
```

## 错误处理

### 配置测试失败

- 显示具体错误信息
- 红色文本提示
- 允许重新测试

### 握手失败

- 显示错误原因
- 提供重试按钮
- 记录详细日志

### 推送注册失败

- Toast 提示
- 保持未注册状态
- 可再次尝试

## 性能优化

### 状态指示器

- 异步状态查询
- 取消重复更新
- 资源自动清理

### 配置测试

- 在 IO 线程执行
- 避免主线程阻塞
- 测试数据很小 (< 100 bytes)

### 握手状态轮询

- 1 秒间隔
- 60 秒超时
- 协程作用域管理

## 安全考虑

### API Key 显示

- 部分掩码显示 (前 4 位和后 4 位)
- 中间部分用 * 替代
- 可选择性显示完整 key

### 端点隐私

- 只在握手成功后显示
- 可复制但不可编辑
- 不记录到普通日志

## 可访问性

- 所有文本都有 contentDescription
- 支持屏幕阅读器
- 足够的点击区域 (48dp 最小)
- 清晰的状态反馈

## 国际化

- 所有文本都已提取到字符串资源
- 支持 RTL 布局
- 使用 plurals 处理复数

## 测试建议

### 单元测试

```kotlin
@Test
fun testConfigStateTransition() {
    // 测试配置状态转换
}

@Test
fun testHandshakeStatusPolling() {
    // 测试握手状态轮询
}
```

### UI 测试

```kotlin
@Test
fun testConfigurationFlow() {
    // 测试完整配置流程
}

@Test
fun testHandshakeDialogFlow() {
    // 测试握手对话框交互
}
```

## 已知限制

1. **群聊不支持**: v3 初期只支持私聊
2. **单一端点**: 每个用户只有一个全局 UnifiedPush 端点
3. **手动握手**: 需要用户主动发起握手
4. **轮询机制**: 握手状态通过轮询而非推送更新

## 后续改进方向

1. **自动握手**: 首次发送消息时自动发起握手
2. **批量握手**: 一次为多个联系人建立通道
3. **通道管理**: 查看和管理所有 v3 通道
4. **推送测试**: 测试 UnifiedPush 连接性
5. **配额监控**: 显示 IPFS Gateway 配额使用情况
6. **性能统计**: 显示 v3 传输统计数据

## 总结

Phase 4 成功实现了 Tap v3 的完整 UI 层,包括配置管理、握手流程和状态显示。所有组件都遵循 Signal 的设计规范,提供了直观友好的用户体验。代码结构清晰,易于维护和扩展。
