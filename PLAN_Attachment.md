# Signal Android v2 Mode 附件消息支持 - 实现计划文档

## 项目概述

根据对`app\src\main\java\org\thoughtcrime\securesms\cos`和`app\src\main\java\org\thoughtcrime\securesms\coscomm`两个模块的深入分析，当前v2 mode已经实现了完整的一对一文本消息通信功能。现需要在不影响现有文本消息系统和轮询机制的前提下，添加附件消息（图片、视频、音频、文件）的v2 mode支持。

## 当前实现状态分析

### 已实现的附件相关功能

1. **数据结构完整**
   - `CosMessage`包含`attachmentInfo: AttachmentInfo?`字段
   - 支持`MessageType.ATTACHMENT`类型枚举
   - `AttachmentInfo`数据结构包含文件名、MIME类型、大小、附件ID

2. **发送端基础设施**
   - `CosAttachmentManager`: 完整的附件加密/解密、上传/下载功能
   - `CosMessageSendManager`: 附件预处理、信息创建、打包功能
   - `CosMessageService`: COS附件上传下载API封装
   - 支持单附件和多附件ZIP打包

3. **路由和集成**
   - `MessageSendRoutingManager`: 智能路由决策支持附件
   - `SignalMessageSendIntegrator`: 发送集成器基础框架

### 关键缺失环节

1. **接收端附件处理缺失**
   - `CosMessageProcessor`没有处理`MessageType.ATTACHMENT`的逻辑
   - 缺少附件下载和解密流程
   - 缺少与Signal原生附件系统的集成

2. **Signal系统集成不完整**
   - 缺少`DatabaseAttachment`记录创建
   - 缺少附件文件到Signal存储位置的保存
   - 缺少`AttachmentDownloadJob`替代机制

3. **UI层集成缺失**
   - 附件消息在聊天界面的显示
   - 附件下载进度和状态显示
   - 附件预览和操作界面

## 详细实现计划

### 阶段一：接收端附件处理核心实现 [优先级: P0]

#### 任务 1.1: 扩展CosMessageProcessor附件处理能力
- **目标**: 在消息处理器中添加附件消息识别和处理逻辑
- **实现要点**:
  - 在`CosMessageProcessor.processIndividualMessage()`中检测`MessageType.ATTACHMENT`
  - 调用`CosAttachmentManager.downloadAttachmentFromCos()`下载附件
  - 处理附件下载失败的重试机制
  - 确保与现有文本消息处理流程不冲突
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/processor/CosMessageProcessor.kt`

#### 任务 1.2: 附件下载解密集成流程
- **目标**: 实现完整的附件接收、下载、解密工作流
- **实现要点**:
  - 从`CosMessage.attachmentInfo`提取附件信息
  - 使用COS临时凭证下载加密附件文件
  - 调用`CosAttachmentManager.decryptAttachment()`解密
  - 处理下载过程中的网络异常和重试
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/CosAttachmentManager.kt`
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/CosMessageService.kt`

#### 任务 1.3: 多附件ZIP包处理
- **目标**: 支持接收和解包多附件ZIP文件
- **实现要点**:
  - 检测ZIP格式的附件包
  - 解压并提取各个附件文件
  - 恢复原始附件的文件名和元数据
  - 处理解压失败和部分成功的情况
- **新增功能**: 在`CosAttachmentManager`中添加ZIP解包方法

### 阶段二：Signal原生系统集成 [优先级: P0]

#### 任务 2.1: DatabaseAttachment记录创建
- **目标**: 将COS下载的附件转换为Signal原生附件记录
- **实现要点**:
  - 分析`DatabaseAttachment`构造参数要求
  - 创建符合Signal标准的附件记录
  - 设置正确的`transferState`为已下载状态
  - 处理附件哈希和元数据
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/processor/CosMessageProcessor.kt`
  - 参考`app/src/main/java/org/thoughtcrime/securesms/database/AttachmentTable.kt`

#### 任务 2.2: 附件文件存储集成
- **目标**: 将解密后的附件保存到Signal的附件存储系统
- **实现要点**:
  - 使用`AttachmentTable.finalizeAttachmentAfterDownload()`
  - 确保文件保存在正确的Signal附件目录
  - 生成正确的附件URI和访问权限
  - 处理存储空间不足等异常情况
- **集成点**: 与Signal原生附件存储系统对接

#### 任务 2.3: 消息记录完整性
- **目标**: 确保包含附件的消息在Signal数据库中正确记录
- **实现要点**:
  - 在`Content`和`DataMessage`中正确设置附件指针
  - 确保`MessageContentProcessor`能正确处理附件消息
  - 维护消息与附件的关联关系
  - 处理附件下载失败时的消息状态
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/processor/CosMessageProcessor.kt`

### 阶段三：发送端优化和完善 [优先级: P1]

#### 任务 3.1: 发送端附件预处理优化
- **目标**: 优化现有附件发送流程的性能和可靠性
- **实现要点**:
  - 完善`CosMessageSendManager.prepareAttachments()`的错误处理
  - 优化大文件处理的内存使用
  - 增加发送进度回调机制
  - 处理发送过程中的网络中断
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/CosMessageSendManager.kt`

#### 任务 3.2: 附件类型和限制管理
- **目标**: 实现合理的附件类型过滤和大小限制
- **实现要点**:
  - 定义v2 mode支持的附件类型白名单
  - 实现附件大小和数量限制检查
  - 对不支持的附件类型提供明确提示
  - 确保与Signal原生限制保持一致
- **配置文件**: 新增附件类型和限制配置

#### 任务 3.3: 发送状态追踪增强
- **目标**: 为附件发送提供详细的状态追踪
- **实现要点**:
  - 扩展`MessageSendStatusTracker`支持附件状态
  - 追踪附件上传进度和完成状态
  - 处理附件发送失败的回退逻辑
  - 提供发送状态的持久化存储
- **修改文件**:
  - `app/src/main/java/org/thoughtcrime/securesms/coscomm/manager/MessageSendStatusTracker.kt`

### 阶段四：用户界面和体验 [优先级: P1, 部分跳过,事实上我们应该复用原有的Signal UI,而不是对UI进行修改]

#### 任务 4.1: 聊天界面附件显示
- **目标**: 在聊天界面正确显示v2 mode接收的附件消息
- **实现要点**:
  - 确保附件消息使用正确的消息气泡样式
  - 显示附件类型图标和文件信息
  - 支持附件预览和全屏查看
  - 区分v2 mode和Signal Server的附件消息来源
- **UI文件**: 聊天界面相关的Fragment和Adapter

#### 任务 4.2: 附件下载状态指示
- **目标**: 为用户提供清晰的附件下载状态反馈
- **实现要点**:
  - 显示附件下载进度条
  - 提供下载失败的重试选项
  - 支持手动暂停/恢复下载
  - 显示网络状态和COS连接状态
- **UI组件**: 下载状态指示器和进度条

#### 任务 4.3: 附件操作菜单
- **目标**: 提供完整的附件操作功能
- **实现要点**:
  - 支持附件保存到本地相册/文件
  - 提供附件分享功能
  - 支持附件删除和重新下载
  - 显示附件来源（v2 mode vs Signal Server）
- **UI组件**: 长按菜单和操作对话框

### 阶段五：可靠性和错误处理 [优先级: P2]

#### 任务 5.1: 网络异常处理机制
- **目标**: 建立健壮的网络异常和重试机制
- **实现要点**:
  - 实现指数退避重试策略
  - 处理COS服务器不可用的情况
  - 支持网络切换时的连接恢复
  - 提供离线消息缓存机制

#### 任务 5.2: 存储空间管理
- **目标**: 合理管理附件存储空间和清理机制
- **实现要点**:
  - 监控附件存储空间使用情况
  - 提供附件清理和压缩选项
  - 实现过期附件自动清理
  - 处理存储空间不足的优雅降级

#### 任务 5.3: 数据一致性保障
- **目标**: 确保附件数据的完整性和一致性
- **实现要点**:
  - 实现附件下载的事务性操作
  - 处理应用崩溃时的数据恢复
  - 验证附件文件的完整性（哈希校验）
  - 同步附件状态和消息状态

### 阶段六：性能优化和测试 [优先级: P3, 暂时跳过]

#### 任务 6.1: 性能优化
- **目标**: 优化附件处理的性能和资源使用
- **实现要点**:
  - 优化大文件加密/解密的内存使用
  - 实现附件的分块下载和处理
  - 优化并发附件处理的线程管理
  - 减少UI线程阻塞

#### 任务 6.2: 兼容性测试
- **目标**: 确保与现有功能的完全兼容
- **测试要点**:
  - 测试与文本消息混合发送的场景
  - 验证轮询机制不受影响
  - 测试Signal Server回退的完整性
  - 验证与群组消息的隔离

#### 任务 6.3: 端到端测试
- **目标**: 建立完整的附件功能测试用例
- **测试要点**:
  - 测试各种附件类型的发送接收
  - 验证不同网络条件下的行为
  - 测试异常情况的恢复能力
  - 性能基准测试和回归测试

## 实施优先级

1. **高优先级（P0）**: 阶段一、阶段二 - 核心功能实现
2. **中优先级（P1）**: 阶段三、阶段四 - 用户体验完善
3. **低优先级（P2）**: 阶段五、阶段六 - 优化和测试

## 关键注意事项

1. **保持向后兼容**: 确保所有更改不影响现有的文本消息功能
2. **遵循现有架构**: 复用现有的COS客户端、轮询机制、路由决策
3. **错误隔离**: 附件处理错误不应影响文本消息的正常收发
4. **渐进式实现**: 可以先支持单个附件，再扩展到多附件
5. **测试覆盖**: 每个阶段完成后进行充分测试再进入下一阶段

## 预估工作量

- **核心功能实现（阶段一、二）**: 15-20个工作日
- **用户体验优化（阶段三、四）**: 8-10个工作日  
- **测试和优化（阶段五、六）**: 5-8个工作日
- **总计**: 28-38个工作日

## 关键技术决策

1. **复用现有COS基础设施**: 不重新实现COS客户端和认证机制
2. **保持轮询机制不变**: 附件消息通过现有轮询系统接收
3. **集成Signal原生附件系统**: 确保附件在Signal中的一致体验
4. **错误隔离设计**: 附件功能异常不影响文本消息正常工作
5. **渐进式交付**: 优先实现核心功能，再完善用户体验

## 风险评估和缓解

1. **风险**: Signal原生附件系统集成复杂
   - **缓解**: 深入研究AttachmentDownloadJob和AttachmentTable实现
   
2. **风险**: 大文件处理可能影响性能
   - **缓解**: 实现分块处理和内存优化
   
3. **风险**: 网络异常处理复杂
   - **缓解**: 复用现有COS客户端的重试机制
   
4. **风险**: UI集成可能影响现有界面
   - **缓解**: 采用最小化改动的集成方案

这个计划确保了在不破坏现有功能的前提下，为v2 mode添加完整的附件消息支持。

## 路径一致性修复记录 (2024年修复)

### 问题描述
发现发送端和接收端的附件路径生成逻辑不一致，导致附件下载失败：

**发送端路径**: `/v2-channels/{channelDirectory}/outbox/attachments/{attachmentId}_{randomSuffix}.bin`
**接收端路径**: `outbox/attachments/{attachmentId}_{randomSuffix}.bin`

### 修复方案
在`AttachmentInfo`中添加`cosPath`字段，确保发送端和接收端使用一致的路径：

#### 1. 数据结构修改
- 在`AttachmentInfo`中添加`cosPath: String?`字段
- 保持向后兼容性，cosPath为可选字段

#### 2. 发送端修改
- 修改`CosMessageSendManager.createAttachmentInfo()`方法
- 从通道信息中提取`channelDirectory`
- 生成完整的v2通道路径格式
- 使用安全的随机后缀生成方式

#### 3. 接收端修改
- 修改`CosAttachmentManager.downloadAttachmentFromCos()`方法
- 优先使用消息中的`cosPath`，如果为空则回退到生成路径
- 修改`CosMessageService`中的相关逻辑

#### 4. 上传端修改
- 修改`CosMessageService.uploadMessage()`方法
- 使用消息中指定的路径而不是重新生成

### 修复后的工作流程
1. **发送端**: 创建AttachmentInfo时生成正确的cosPath
2. **消息传输**: cosPath通过加密消息传递到接收端  
3. **接收端**: 直接使用消息中的cosPath下载附件
4. **向后兼容**: 如果cosPath为空，自动回退到旧逻辑

### 测试验证
- 添加了路径一致性测试用例
- 验证序列化/反序列化过程中cosPath保持不变
- 确保新旧消息格式都能正常处理

### 影响评估
- ✅ **不影响文本消息**: 只修改附件相关逻辑
- ✅ **不影响轮询机制**: 保持现有轮询和消息获取流程
- ✅ **向后兼容**: 支持没有cosPath的旧消息
- ✅ **安全性**: 使用SecureRandom生成随机后缀
- ✅ **一致性**: 发送端和接收端使用相同路径

## ID格式不匹配修复记录

### 问题描述
v2 mode文本消息可以正常收发，但附件下载失败，报错"没有有效的子账户凭证"。

经过分析发现：
- **文本消息轮询**: 使用存储时的RecipientId格式查找子账户，成功
- **附件下载**: 使用ServiceId格式查找子账户，失败

### 根本原因
子账户权限池中的键使用RecipientId格式存储，但附件下载时传递的是ServiceId格式：

```kotlin
// 错误的调用方式
cosAttachmentManager.downloadAttachmentFromCos(
    recipientId = cosMessage.senderId,  // ServiceId格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
    // ...
)

// 子账户池查找失败
subAccountPoolManager.getValidReceivedSubAccount(recipientId) // 找不到匹配的键
```

### 修复方案（最小修改原则）
在`CosMessageProcessor.downloadAndDecryptAttachment()`方法中添加ID格式转换：

```kotlin
// 将ServiceId格式转换为RecipientId格式
val senderRecipient = getSenderRecipient(senderId)
val recipientIdForCos = senderRecipient.id.toString()

cosAttachmentManager.downloadAttachmentFromCos(
    recipientId = recipientIdForCos,  // 使用RecipientId格式
    // ...
)
```

### 修复特点
✅ **最小修改**: 只修改1个方法，10行代码
✅ **不影响文本消息**: 文本消息流程完全不变  
✅ **不影响轮询机制**: 轮询和消息获取机制保持不变
✅ **不影响加密**: Double Ratchet加密和消息排序逻辑不变
✅ **向后兼容**: `getSenderRecipient()`已支持两种ID格式

### 测试验证
- 添加ID格式转换的单元测试
- 验证ServiceId格式识别和转换逻辑
- 确保修复不影响现有功能

### 影响评估  
- **风险等级**: 极低 - 只影响附件下载的ID查找逻辑
- **影响范围**: 仅限于v2 mode附件功能
- **回滚方案**: 简单回退单个方法的修改
