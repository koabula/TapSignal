# Tap推送机制架构修改方案：从IoT到API Gateway

## 一、问题分析

### 1.1 当前IoT方案的问题

**AWS IoT Core / 腾讯云IoT Hub的不必要复杂性**：
- 需要管理设备证书和密钥
- 需要创建IoT Policy
- 需要管理MQTT Topic和订阅关系
- 需要处理设备连接状态同步
- 定价相对较高（按设备连接时长和消息数计费）

**过度设计**：
- Tap只需要简单的"有新消息"通知
- 不需要IoT的双向控制、设备影子、规则引擎等功能
- MQTT协议增加了不必要的复杂度

### 1.2 API Gateway的优势

**AWS API Gateway WebSocket / 腾讯云API网关WebSocket**：
- ✅ 原生WebSocket支持，无需MQTT层
- ✅ ConnectionId自动管理，无需手动注册设备
- ✅ 成本更低（按连接时长和消息数计费，通常比IoT便宜50%+）
- ✅ 更简单的权限模型（Lambda集成）
- ✅ 自动处理连接/断开事件

---

## 二、架构对比

### 2.1 当前架构（IoT）

```
Client → MQTT over WebSocket → IoT Core → (Topic: tap/notifications/{topicId})
                                    ↑
                                Webhook Lambda发布消息
```

**关键组件**：
- IoT设备注册
- IoT Policy管理
- MQTT客户端库
- Topic订阅管理
- 设备证书/密钥存储

### 2.2 新架构（API Gateway）

```
Client → WebSocket → API Gateway → Lambda (onMessage) → DynamoDB (connectionId存储)
                         ↑
                    Webhook Lambda直接推送
```

**关键组件**：
- API Gateway WebSocket API
- DynamoDB存储connectionId映射
- Lambda函数：$connect, $disconnect, $default, sendNotification
- 简化的WebSocket客户端

---

##三、核心修改设计

### 3.1 数据流变化

#### 旧流程（IoT）：
1. 客户端连接IoT Core，订阅Topic: `tap/notifications/{topicId}`
2. Webhook收到通知，发布MQTT消息到该Topic
3. IoT Core将消息推送给订阅者

#### 新流程（API Gateway）：
1. 客户端连接API Gateway WebSocket，`$connect`触发Lambda保存connectionId
2. Webhook收到通知，查询connectionId，直接推送WebSocket消息
3. 客户端收到WebSocket消息

### 3.2 ConnectionId映射方案

**DynamoDB表结构**：
```
Table: tap-ws-connections
Primary Key: userId (String)
Attributes:
  - connectionId (String)
  - connectedAt (Number)
  - ttl (Number, 24小时过期)
```

**工作流程**：
1. `$connect`: Lambda将 `userId -> connectionId` 写入DynamoDB
2. Webhook: 根据receiverId查询connectionId
3. Webhook: 调用API Gateway Management API推送消息
4. `$disconnect`: Lambda清理connectionId

### 3.3 接口保持不变

**关键点**：`NotificationProvider`和`NotificationDeployer`接口无需改变！

只需修改实现类：
- `AwsIoTNotificationProvider` → `AwsApiGatewayNotificationProvider`
- `TencentIoTHubNotificationProvider` → `TencentApiGatewayNotificationProvider`
- `AwsIoTDeployer` → `AwsApiGatewayDeployer`
- `TencentIoTHubDeployer` → `TencentApiGatewayDeployer`

---

## 四、详细修改计划

### 4.1 目录结构调整

```
tap/notification/provider/
├── aws/
│   ├── AwsApiGatewayNotificationProvider.kt    [新建，替代AwsIoTNotificationProvider]
│   ├── AwsApiGatewayDeployer.kt                [新建，替代AwsIoTDeployer]
│   ├── AwsWebSocketClient.kt                   [新建，替代AwsIoTClient]
│   ├── [删除] AwsIoTNotificationProvider.kt
│   ├── [删除] AwsIoTDeployer.kt
│   └── [删除] AwsIoTClient.kt
│
└── tencent/
    ├── TencentApiGatewayNotificationProvider.kt [新建]
    ├── TencentApiGatewayDeployer.kt             [新建]
    ├── TencentWebSocketClient.kt                [新建]
    ├── [删除] TencentIoTHubNotificationProvider.kt
    ├── [删除] TencentIoTHubDeployer.kt
    └── [删除] TencentIoTHubClient.kt
```

### 4.2 Lambda函数修改

#### AWS Lambda函数清单

**1. WebSocket管理函数**（新建）：
```
aws-ws-connect.js        # $connect路由，保存connectionId
aws-ws-disconnect.js     # $disconnect路由，清理connectionId
aws-ws-default.js        # $default路由，处理心跳
```

**2. Webhook推送函数**（修改）：
```
aws-webhook.js
修改点：
- 删除IoT发布逻辑
- 添加DynamoDB查询connectionId
- 添加API Gateway Management API推送逻辑
```

**3. S3触发器函数**（保持不变）：
```
aws-f-a.js              # 保持不变，继续调用Webhook
```

#### 腾讯云函数清单

**1. WebSocket管理函数**（新建）：
```
tencent-ws-register.js   # 注册路由，保存connectionId
tencent-ws-cleanup.js    # 清理路由，删除connectionId
```

**2. Webhook推送函数**（修改）：
```
tencent-webhook.js
修改点：
- 删除IoT Hub发布逻辑
- 添加云数据库查询connectionId
- 添加API网关推送API调用
```

**3. COS触发器函数**（保持不变）：
```
tencent-f-a.js          # 保持不变
```

### 4.3 部署器修改要点

#### AwsApiGatewayDeployer核心方法

```kotlin
suspend fun deployWebhook(): String {
    // 1. 创建DynamoDB表（如不存在）
    // 2. 部署WebSocket API
    //    - $connect → aws-ws-connect Lambda
    //    - $disconnect → aws-ws-disconnect Lambda
    //    - $default → aws-ws-default Lambda
    // 3. 部署Webhook Lambda（修改版）
    // 4. 配置IAM权限：
    //    - Lambda访问DynamoDB
    //    - Lambda调用API Gateway Management API
    // 5. 返回WebSocket URL
}

suspend fun deployPushService(): PushServiceInfo {
    // 返回WebSocket API端点信息
    return PushServiceInfo(
        endpoint = "wss://{api-id}.execute-api.{region}.amazonaws.com/prod",
        region = region,
        credentials = mapOf("apiGatewayId" to apiId),
        metadata = mapOf("type" to "api-gateway")
    )
}
```

#### TencentApiGatewayDeployer核心方法

```kotlin
suspend fun deployWebhook(): String {
    // 1. 创建云数据库集合（如不存在）
    // 2. 创建WebSocket API网关服务
    // 3. 配置路由：
    //    - $connect → register函数
    //    - $disconnect → cleanup函数
    // 4. 部署Webhook云函数（修改版）
    // 5. 配置权限
    // 6. 返回WebSocket URL
}
```

### 4.4 客户端连接管理修改

#### AwsApiGatewayNotificationProvider

```kotlin
suspend fun connect(userId: String, onNotification: (NotificationMessage) -> Unit): ConnectionResult {
    // 1. 连接到WebSocket端点
    //    URL: wss://{api-id}.execute-api.{region}.amazonaws.com/prod?userId={userId}
    // 2. 等待连接成功（$connect Lambda保存connectionId）
    // 3. 设置消息监听器
    // 4. 返回connectionId
}
```

**简化点**：
- ❌ 无需设备证书
- ❌ 无需IoT Policy
- ❌ 无需Topic订阅
- ✅ 直接WebSocket连接
- ✅ 自动获得connectionId

### 4.5 配置数据结构调整

#### NotificationConfig修改

```kotlin
data class NotificationConfig(
    val provider: String,              // "aws-api-gateway" | "tencent-api-gateway"
    val webhookUrl: String,
    val notifySecret: String,
    val pushServiceInfo: PushServiceInfo,  // 保持不变
    val deployedAt: Long,
    val version: String = "2.0"        // 版本号更新
)

data class PushServiceInfo(
    val endpoint: String,              // WebSocket URL
    val region: String,
    val credentials: Map<String, String>,  // {"apiGatewayId": "xxx"}
    val metadata: Map<String, Any>     // {"type": "api-gateway"}
)
```

#### ContactNotificationConfig修改

```kotlin
data class ContactNotificationConfig(
    val contactId: String,
    val platform: String,              // "aws-api-gateway" | "tencent-api-gateway"
    val webhookUrl: String,
    val notifySecret: String,
    val userId: String,                // [改] 替代topicId，用于查询connectionId
    val lastUpdated: Long,
    val verified: Boolean = false
)
```

---

## 五、实施步骤

### Step 1: 准备Lambda/云函数代码
- [ ] 编写aws-ws-connect.js
- [ ] 编写aws-ws-disconnect.js
- [ ] 修改aws-webhook.js（移除IoT逻辑，添加API Gateway推送）
- [ ] 编写tencent-ws-register.js
- [ ] 编写tencent-ws-cleanup.js
- [ ] 修改tencent-webhook.js
- [ ] 打包并放入assets/lambda-functions/

### Step 2: 实现新的Provider
- [ ] 实现AwsApiGatewayNotificationProvider
  - WebSocket客户端集成
  - 连接/断开管理
  - 消息监听器
- [ ] 实现TencentApiGatewayNotificationProvider
  - 腾讯云WebSocket客户端
  - 连接管理

### Step 3: 实现新的Deployer
- [x] 实现AwsApiGatewayDeployer
  - DynamoDB表创建
  - API Gateway WebSocket API部署
  - Lambda函数部署和配置
  - IAM权限设置
- [x] 实现TencentApiGatewayDeployer
  - 云数据库集合创建
  - API网关服务创建
  - 云函数部署
  - 权限配置

### Step 4: 修改Factory和Manager
- [ ] 修改NotificationProviderFactory
  - 更新provider类型识别（"aws-api-gateway", "tencent-api-gateway"）
  - 创建新的Provider实例
- [ ] 修改NotificationManager（如需）
  - 适配新的连接管理方式

### Step 5: 删除旧IoT代码
- [ ] 删除AwsIoTNotificationProvider.kt
- [ ] 删除AwsIoTDeployer.kt
- [ ] 删除AwsIoTClient.kt
- [ ] 删除TencentIoTHubNotificationProvider.kt
- [ ] 删除TencentIoTHubDeployer.kt
- [ ] 删除TencentIoTHubClient.kt
- [ ] 删除旧的Lambda函数代码（如aws-webhook-iot.zip）

### Step 6: 测试和验证
- [ ] 单元测试
- [ ] AWS端到端测试
- [ ] 腾讯云端到端测试
- [ ] 跨平台通知测试
- [ ] 压力测试

---

## 六、关键技术细节

### 6.1 WebSocket连接URL格式

**AWS**：
```
wss://{api-id}.execute-api.{region}.amazonaws.com/prod?userId={userId}
```

**腾讯云**：
```
wss://{service-id}.{region}.apigateway.myqcloud.com/?userId={userId}
```

### 6.2 推送消息流程

```
1. B上传消息到COS/S3
2. 触发F_B云函数
3. F_B读取A的webhook配置: {webhookUrl, notifySecret, userId: A_userId}
4. F_B调用A的Webhook: POST webhookUrl
   Body: {senderId: B_hash, userId: A_userId, timestamp, signature}
5. A的Webhook处理：
   - 验证签名
   - 查询DynamoDB: userId=A_userId → connectionId
   - 调用API Gateway Management API推送消息到connectionId
6. A的Client收到WebSocket消息
7. A触发下载逻辑
```

### 6.3 成本对比

| 服务 | IoT方案 | API Gateway方案 | 节省 |
|------|---------|-----------------|------|
| **AWS** | IoT Core连接时长 + 消息数 | API Gateway连接时长 + 消息数 | ~50% |
| **腾讯云** | IoT Hub连接 + 消息 | API网关WebSocket连接 + 请求数 | ~40% |

---

## 七、兼容性说明

### 7.1 接口兼容

✅ **抽象层接口完全不变**：
- `NotificationProvider`接口
- `NotificationDeployer`接口
- `NotificationManager`调用方式

### 7.2 配置迁移

由于当前没有IoT的生产用户，**无需数据迁移**。

新配置的provider字段改为：
- `"aws-api-gateway"`
- `"tencent-api-gateway"`

---

## 八、风险和缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| WebSocket连接不稳定 | 推送失败 | 自动重连 + 心跳机制 |
| DynamoDB一致性延迟 | 短暂无法推送 | 重试机制 + TTL清理 |
| API Gateway限流 | 高并发推送失败 | 批量推送 + 队列缓冲 |
| ConnectionId过期 | 推送到旧连接 | TTL清理 + 重连刷新 |

---

## 九、预期收益

✅ **简化40%代码**：移除IoT设备管理、证书管理、MQTT逻辑  
✅ **降低50%成本**：API Gateway定价更低  
✅ **提升部署速度**：减少部署步骤（无需IoT Policy、设备注册）  
✅ **提升可维护性**：更简单的架构，更少的组件  
✅ **提升连接稳定性**：原生WebSocket，更好的浏览器/客户端支持

---

**文档版本**: 2.0  
**创建日期**: 2025-11-02  
**预计工期**: 8-12天

