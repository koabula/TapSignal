# 子账户持久化问题修复总结

## 问题描述

在Signal应用重启时，COS子账户持久化数据加载失败，出现以下错误：

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of `org.thoughtcrime.securesms.coscomm.manager.SubAccountEntry` (no Creators, like default constructor, exist): cannot deserialize from Object value (no delegate- or property-based Creator)
```

## 问题根源

**SubAccountEntry类缺少JSON序列化注解**

### 错误的实现（修复前）

```kotlin
data class SubAccountEntry(
    val recipientId: String,                    // ❌ 缺少@JsonProperty注解
    val accessInfo: CosAccessInfo,              // ❌ 缺少@JsonProperty注解
    val isActive: Boolean = true,               // ❌ 缺少@JsonProperty注解
    val pollingErrors: Int = 0,                 // ❌ 缺少@JsonProperty注解
    val createdAt: Long = System.currentTimeMillis(),  // ❌ 缺少@JsonProperty注解
    val lastUsed: Long = System.currentTimeMillis()    // ❌ 缺少@JsonProperty注解
) {
    fun isValid(): Boolean {                    // ❌ 计算属性被意外序列化
        return isActive && !accessInfo.isExpired() && pollingErrors < 5
    }
}
```

### 问题分析

1. **缺少序列化注解**：Jackson需要`@JsonProperty`注解来正确映射JSON字段
2. **计算属性污染**：`isValid()`方法的结果被序列化到JSON中
3. **字段名不匹配**：JSON中的`"active"`字段与Kotlin的`isActive`属性不匹配
4. **未知属性处理**：ObjectMapper没有配置忽略未知属性

## 修复方案

### 1. 添加JSON序列化注解

**修复后：**
```kotlin
data class SubAccountEntry(
    @JsonProperty("recipientId")
    val recipientId: String,
    
    @JsonProperty("accessInfo")
    val accessInfo: CosAccessInfo,
    
    @JsonProperty("active")                     // ✅ 匹配JSON中的字段名
    val isActive: Boolean = true,
    
    @JsonProperty("pollingErrors")
    val pollingErrors: Int = 0,
    
    @JsonProperty("createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    
    @JsonProperty("lastUsed")
    val lastUsed: Long = System.currentTimeMillis()
) {
    @JsonIgnore                                 // ✅ 防止计算属性被序列化
    fun isValid(): Boolean {
        return isActive && !accessInfo.isExpired() && pollingErrors < 5
    }
}
```

### 2. 配置ObjectMapper

**修复前：**
```kotlin
private val objectMapper = ObjectMapper()      // ❌ 默认配置，不忽略未知属性
```

**修复后：**
```kotlin
private val objectMapper = ObjectMapper().apply {
    // ✅ 忽略未知属性，确保向后兼容性
    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
```

### 3. 添加必要的Import

```kotlin
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
```

## 修复验证

### 1. 序列化测试

创建了`SubAccountSerializationTest`来验证：
- 正常的序列化和反序列化流程
- 从错误日志中的JSON数据反序列化
- 数据一致性验证

### 2. 持久化功能测试

创建了`SubAccountPersistenceFix`来验证：
- SubAccountPoolManager正常初始化
- 现有数据正确加载
- 持久化功能正常工作

## 向后兼容性

修复确保了向后兼容性：

1. **字段映射**：`@JsonProperty("active")`正确映射JSON中的`"active"`字段
2. **忽略未知属性**：配置ObjectMapper忽略JSON中的`"valid"`等计算属性
3. **默认值处理**：保持原有的默认值逻辑

## 测试用例

### 测试JSON数据（来自错误日志）
```json
{
    "accessInfo": {
        "provider": "TENCENT",
        "region": "ap-nanjing",
        "bucketName": "newsignal2-1316759135",
        "accessKeyId": "AKID******************************************",
        "secretAccessKey": "****************************************",
        "sessionToken": null,
        "expireTime": 9223372036854775807,
        "sharedDirectory": "/v2-channels/signal-v2-1754991598501-1467/outbox/"
    },
    "createdAt": 1754991607122,
    "lastUsed": 1754991607122,
    "pollingErrors": 0,
    "recipientId": "RecipientId::3",
    "active": true,
    "valid": true
}
```

### 验证步骤

1. 运行`SubAccountSerializationTest.runAllTests()`
2. 运行`SubAccountPersistenceFix.verifyFix(context)`
3. 检查应用启动时是否还有序列化错误

## 修复效果

修复后的效果：

1. ✅ **应用启动正常**：不再出现序列化异常
2. ✅ **数据正确加载**：持久化的子账户数据正确恢复
3. ✅ **功能正常工作**：COS轮询服务正常初始化
4. ✅ **向后兼容**：现有数据无需迁移即可正常使用

## 预防措施

为了防止类似问题再次发生：

1. **代码规范**：所有用于JSON序列化的数据类都应添加适当的注解
2. **测试覆盖**：为序列化功能添加单元测试
3. **配置统一**：统一配置ObjectMapper的行为
4. **文档说明**：在数据类上添加序列化相关的文档说明

## 总结

这个修复解决了COS子账户持久化的关键问题：
- 修复了JSON序列化注解缺失的问题
- 确保了应用重启后数据正确加载
- 保持了向后兼容性
- 提高了系统的稳定性和可靠性

修复后，用户的COS通道状态将在应用重启后正确恢复，确保v2模式通信功能的连续性。
