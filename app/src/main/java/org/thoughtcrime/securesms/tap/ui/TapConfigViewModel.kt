/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.tap.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*
import org.thoughtcrime.securesms.tap.utils.LogSanitizer

/**
 * Tap配置页面状态
 */
data class TapConfigState(
    val availableProviders: List<ProviderInfo> = emptyList(),
    val selectedProviderType: String? = null,
    val currentProviderDescriptor: ProviderConfigDescriptor? = null,
    val configValues: Map<String, Any> = emptyMap(),
    val hasExistingConfig: Boolean = false,
    val isConfigValid: Boolean = false,
    val testState: ConfigTestState = ConfigTestState.READY,
    val testResult: ConfigTestResult? = null,
    val isLoading: Boolean = false,
    val notificationDeploymentState: NotificationDeploymentState = NotificationDeploymentState.NOT_DEPLOYED,
    val notificationConfig: org.thoughtcrime.securesms.tap.notification.NotificationConfig? = null,
    val deploymentErrorMessage: String? = null,
    val deploymentErrorDetails: String? = null
)

/**
 * 推送服务部署状态
 */
enum class NotificationDeploymentState {
    NOT_DEPLOYED,     // 未部署
    DEPLOYING,        // 部署中
    DEPLOYED,         // 已部署
    FAILED            // 部署失败
}

/**
 * 部署来源
 */
private enum class DeploymentSource {
    AUTO,    // 从 saveConfig 自动触发
    MANUAL   // 从部署按钮手动触发
}

/**
 * 部署执行结果
 */
private data class DeploymentExecutionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val errorDetails: String? = null,
    val config: org.thoughtcrime.securesms.tap.notification.NotificationConfig? = null
)

/**
 * Provider信息
 */
data class ProviderInfo(
    val type: String,
    val displayName: String,
    val description: String,
    val descriptor: ProviderConfigDescriptor
)

/**
 * Tap配置ViewModel
 * 
 * 管理Tap配置页面的状态，包括Provider选择、配置编辑、测试等功能
 */
class TapConfigViewModel : ViewModel() {

    companion object {
        private val TAG = Log.tag(TapConfigViewModel::class.java)
    }

    private val _state = MutableLiveData<TapConfigState>()
    val state: LiveData<TapConfigState> = _state

    private lateinit var context: Context
    private lateinit var configManager: TransportProviderConfigManager
    private lateinit var providerManager: TransportProviderManager
    private lateinit var providerRegistry: ProviderRegistry
    private lateinit var notificationConfigManager: org.thoughtcrime.securesms.tap.notification.NotificationConfigManager
    private lateinit var notificationProviderFactory: org.thoughtcrime.securesms.tap.notification.NotificationProviderFactory

    /**
     * 初始化ViewModel
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.configManager = TransportProviderConfigManager.getInstance(context)
        this.providerManager = TransportProviderManager.getInstance(context)
        this.providerRegistry = ProviderRegistry.getInstance(context)
        this.notificationConfigManager = org.thoughtcrime.securesms.tap.notification.NotificationConfigManager.getInstance(context)
        this.notificationProviderFactory = org.thoughtcrime.securesms.tap.notification.NotificationProviderFactory.getInstance()
        
        // 确保Provider注册中心已初始化（幂等）
        try {
            providerRegistry.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "初始化ProviderRegistry失败: ${LogSanitizer.sanitizeThrowable(e)}")
        }
        
        loadInitialData()
    }

    /**
     * 加载初始数据
     */
    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                _state.value = _state.value?.copy(isLoading = true) ?: TapConfigState(isLoading = true)
                
                // 加载可用的Providers
                val availableProviders = loadAvailableProviders()
                
                // 加载推送服务部署状态
                val notificationConfig = notificationConfigManager.getLocalConfig()
                val deploymentState = if (notificationConfig != null && notificationConfig.validate()) {
                    NotificationDeploymentState.DEPLOYED
                } else {
                    NotificationDeploymentState.NOT_DEPLOYED
                }
                
                // 检查是否有现有配置
                val existingConfigs = configManager.getAllConfigs()
                
                if (existingConfigs.isNotEmpty()) {
                    // 有现有配置，加载第一个
                    val (providerType, config) = existingConfigs.entries.first()
                    val descriptor = availableProviders.find { it.type == providerType }?.descriptor
                    
                    _state.value = TapConfigState(
                        availableProviders = availableProviders,
                        selectedProviderType = providerType,
                        currentProviderDescriptor = descriptor,
                        configValues = config,
                        hasExistingConfig = true,
                        isConfigValid = descriptor?.isConfigComplete(config) == true,
                        isLoading = false,
                        notificationDeploymentState = deploymentState,
                        notificationConfig = notificationConfig,
                        deploymentErrorMessage = null,
                        deploymentErrorDetails = null
                    )
                } else {
                    // 没有现有配置，显示Provider选择
                    _state.value = TapConfigState(
                        availableProviders = availableProviders,
                        isLoading = false,
                        notificationDeploymentState = deploymentState,
                        notificationConfig = notificationConfig,
                        deploymentErrorMessage = null,
                        deploymentErrorDetails = null
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "加载初始数据失败: ${LogSanitizer.sanitizeThrowable(e)}")
                _state.value = _state.value?.copy(isLoading = false) ?: TapConfigState()
            }
        }
    }

    /**
     * 加载可用的Providers
     */
    private fun loadAvailableProviders(): List<ProviderInfo> {
        return try {
            val providerTypes = providerRegistry.getAvailableProviderTypes()
            
            providerTypes.mapNotNull { providerType ->
                val descriptor = providerRegistry.getProviderConfigDescriptor(providerType)
                descriptor?.let {
                    ProviderInfo(
                        type = providerType,
                        displayName = it.displayName,
                        description = it.description,
                        descriptor = it
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载Provider列表失败: ${LogSanitizer.sanitizeThrowable(e)}")
            emptyList()
        }
    }



    /**
     * 选择Provider
     */
    fun selectProvider(providerType: String) {
        viewModelScope.launch {
            try {
                val currentState = _state.value ?: return@launch
                val descriptor = currentState.availableProviders.find { it.type == providerType }?.descriptor
                    ?: return@launch

                // 初始化所有字段的默认值到configValues中
                val initialConfigValues = mutableMapOf<String, Any>()
                descriptor.getConfigFields().forEach { field ->
                    field.defaultValue?.let { defaultValue ->
                        initialConfigValues[field.key] = defaultValue
                    }
                }

                // 计算初始配置的有效性
                val isInitiallyValid = descriptor.isConfigComplete(initialConfigValues)

                _state.value = currentState.copy(
                    selectedProviderType = providerType,
                    currentProviderDescriptor = descriptor,
                    configValues = initialConfigValues,
                    isConfigValid = isInitiallyValid,
                    testState = ConfigTestState.READY,
                    testResult = null
                )

            } catch (e: Exception) {
                Log.e(TAG, "选择Provider失败: ${LogSanitizer.sanitizeThrowable(e)}")
            }
        }
    }

    /**
     * 更新配置值
     */
    fun updateConfigValue(key: String, value: Any) {
        viewModelScope.launch {
            try {
                val currentState = _state.value ?: return@launch
                val newConfigValues = currentState.configValues.toMutableMap().apply {
                    put(key, value)
                }

                val isValid = currentState.currentProviderDescriptor?.isConfigComplete(newConfigValues) == true

                _state.value = currentState.copy(
                    configValues = newConfigValues,
                    isConfigValid = isValid,
                    testState = ConfigTestState.READY, // 重置测试状态
                    testResult = null
                )

            } catch (e: Exception) {
                Log.e(TAG, "更新配置值失败: ${LogSanitizer.sanitizeThrowable(e)}")
            }
        }
    }

    /**
     * 测试配置
     */
    fun testConfig() {
        viewModelScope.launch {
            try {
                val currentState = _state.value ?: return@launch
                val descriptor = currentState.currentProviderDescriptor ?: return@launch
                
                if (!descriptor.supportsConfigTest) {
                    return@launch
                }

                // 设置测试中状态
                _state.value = currentState.copy(
                    testState = ConfigTestState.TESTING,
                    testResult = null
                )

                // 执行测试
                val testResult = descriptor.testConfig(currentState.configValues, context)

                // 更新测试结果
                _state.value = _state.value?.copy(
                    testState = if (testResult.isSuccess) ConfigTestState.SUCCESS else ConfigTestState.FAILED,
                    testResult = testResult
                )

            } catch (e: Exception) {
                Log.e(TAG, "测试配置失败: ${LogSanitizer.sanitizeThrowable(e)}")
                _state.value = _state.value?.copy(
                    testState = ConfigTestState.FAILED,
                    testResult = ConfigTestResult.Failed("测试过程中发生错误：${e.message}")
                )
            }
        }
    }

    /**
     * 保存配置
     */
    fun saveConfig(): Boolean {
        return try {
            val currentState = _state.value ?: return false
            val providerType = currentState.selectedProviderType ?: return false
            val descriptor = currentState.currentProviderDescriptor ?: return false

            // 验证配置
            val validationResult = descriptor.validateConfig(currentState.configValues)
            if (!validationResult.isValid) {
                Log.w(TAG, "配置验证失败: ${validationResult}")
                return false
            }

            // 保存配置
            val success = configManager.saveProviderConfig(providerType, currentState.configValues)
            if (success) {
                Log.i(TAG, "配置保存成功: providerType=$providerType")
                
                // 更新状态
                _state.value = currentState.copy(
                    hasExistingConfig = true
                )
                
                // 异步检查推送服务部署状态，如果未部署则自动触发部署
                viewModelScope.launch {
                    try {
                        val isDeployed = checkNotificationDeployment()
                        if (!isDeployed) {
                            Log.i(TAG, "检测到推送服务未部署，自动触发部署")
                            // 自动触发部署
                            val result = internalPerformDeployment(DeploymentSource.AUTO)
                            updateDeploymentState(result)
                        } else {
                            Log.d(TAG, "推送服务已部署")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "检查推送服务部署失败", e)
                    }
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "保存配置失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }

    /**
     * 删除配置
     */
    fun deleteConfig(): Boolean {
        return try {
            val currentState = _state.value ?: return false
            val providerType = currentState.selectedProviderType ?: return false

            val success = configManager.deleteProviderConfig(providerType)
            if (success) {
                Log.i(TAG, "配置删除成功: providerType=$providerType")
                
                // 重置状态到选择Provider界面
                _state.value = currentState.copy(
                    selectedProviderType = null,
                    currentProviderDescriptor = null,
                    configValues = emptyMap(),
                    hasExistingConfig = false,
                    isConfigValid = false,
                    testState = ConfigTestState.READY,
                    testResult = null
                )
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "删除配置失败: ${LogSanitizer.sanitizeThrowable(e)}")
            false
        }
    }

    /**
     * 重新选择Provider
     */
    fun backToProviderSelection() {
        val currentState = _state.value ?: return
        _state.value = currentState.copy(
            selectedProviderType = null,
            currentProviderDescriptor = null,
            configValues = emptyMap(),
            isConfigValid = false,
            testState = ConfigTestState.READY,
            testResult = null
        )
    }
    
    /**
     * 部署推送服务（手动触发）
     */
    fun deployNotificationService() {
        viewModelScope.launch {
            val result = internalPerformDeployment(DeploymentSource.MANUAL)
            updateDeploymentState(result)
        }
    }
    
    /**
     * 统一的部署方法
     * 
     * @param source 部署来源（自动或手动）
     * @return 部署执行结果
     */
    private suspend fun internalPerformDeployment(source: DeploymentSource): DeploymentExecutionResult {
        return try {
            val currentState = _state.value ?: return DeploymentExecutionResult(
                success = false,
                errorMessage = "状态为空",
                errorDetails = "无法获取当前配置状态"
            )
            
            // 检查配置是否有效
            if (!currentState.isConfigValid) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "配置无效",
                    errorDetails = "请先完成Provider配置并确保所有必填项都已填写"
                )
            }
            
            // 获取API Key用于检测provider类型
            // 注意：配置界面保存的键名是secretId，因此优先从secretId读取，也兼容apiKey键名
            val apiKey = (currentState.configValues["apiKey"] as? String)
                ?: (currentState.configValues["secretId"] as? String)
                ?: (currentState.configValues["accessKeyId"] as? String)
            if (apiKey.isNullOrEmpty()) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "API Key为空",
                    errorDetails = "无法检测Provider类型，请检查API Key配置（secretId/accessKeyId字段）"
                )
            }
            
            // 检测provider类型
            val detectedProviderType = notificationProviderFactory.detectProviderType(apiKey)
            if (detectedProviderType == null) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "无法识别API Key类型",
                    errorDetails = "API Key格式不正确，无法识别为AWS或腾讯云凭证"
                )
            }
            
            Log.i(TAG, "开始部署推送服务: providerType=$detectedProviderType, source=$source")
            
            // 设置部署中状态
            _state.value = currentState.copy(
                notificationDeploymentState = NotificationDeploymentState.DEPLOYING,
                deploymentErrorMessage = null,
                deploymentErrorDetails = null
            )
            
            // 创建NotificationProvider
            val secretKeyValue: String = currentState.configValues["secretKey"] as? String ?: ""
            val regionValue: String = currentState.configValues["region"] as? String ?: ""
            val credentials = mapOf(
                "apiKey" to apiKey,
                "secretKey" to secretKeyValue,
                "region" to regionValue
            )
            
            val notificationProvider = notificationProviderFactory.createProvider(
                context,
                detectedProviderType,
                credentials
            )
            
            if (notificationProvider == null) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "创建NotificationProvider失败",
                    errorDetails = "无法创建推送服务提供商实例，请检查Provider类型和凭证配置"
                )
            }
            
            // 执行部署
            val deployResult = notificationProvider.deploy(
                apiKey = apiKey,
                region = credentials["region"] ?: ""
            )
            
            if (!deployResult.success) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "部署失败",
                    errorDetails = deployResult.errorMessage ?: "部署过程中发生未知错误"
                )
            }
            
            // 获取部署的配置
            val webhookConfig = notificationProvider.getWebhookConfig()
            val pushServiceInfo = deployResult.pushServiceInfo
            
            if (webhookConfig == null || pushServiceInfo == null) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "获取部署配置失败",
                    errorDetails = "部署完成但无法获取Webhook配置或推送服务信息"
                )
            }
            
            // 创建通知配置
            val notificationConfig = org.thoughtcrime.securesms.tap.notification.NotificationConfig(
                provider = detectedProviderType,
                webhookUrl = webhookConfig.webhookUrl,
                notifySecret = webhookConfig.notifySecret,
                pushServiceInfo = pushServiceInfo,
                deployedAt = System.currentTimeMillis(),
                version = "1.0"
            )
            
            // 验证部署配置
            val validationResult = validateDeployment(notificationConfig)
            if (!validationResult.success) {
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "部署验证失败",
                    errorDetails = validationResult.errorDetails ?: "部署的配置验证未通过"
                )
            }
            
            // 方案三：确保配置保存到本地数据库成功后才返回成功
            Log.i(TAG, "开始保存推送服务配置到本地数据库...")
            val saveSuccess = notificationConfigManager.saveLocalConfig(notificationConfig)
            if (!saveSuccess) {
                Log.e(TAG, "保存配置到本地数据库失败，部署失败")
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "保存配置失败",
                    errorDetails = "部署成功但无法保存本地配置，请检查存储权限"
                )
            }
            
            // 验证配置是否已保存到本地数据库
            val savedConfig = notificationConfigManager.getLocalConfig()
            if (savedConfig == null) {
                Log.e(TAG, "配置保存后验证失败，本地数据库中未找到配置")
                return DeploymentExecutionResult(
                    success = false,
                    errorMessage = "配置验证失败",
                    errorDetails = "配置保存后验证失败，本地数据库中未找到配置记录"
                )
            }
            
            Log.i(TAG, "推送服务部署成功: providerType=$detectedProviderType, 配置已保存到本地数据库")
            
            // 重新连接WebSocket：断开旧连接，使用新配置重新初始化
            try {
                Log.i(TAG, "部署成功后重新初始化推送服务连接...")
                val notificationManager = org.thoughtcrime.securesms.tap.notification.NotificationManager.getInstance(context)
                
                // 断开旧的WebSocket连接
                notificationManager.disconnect()
                Log.i(TAG, "已断开旧的WebSocket连接")
                
                // 创建新的NotificationProvider
                val factory = org.thoughtcrime.securesms.tap.notification.NotificationProviderFactory.getInstance()
                val newProvider = factory.createProvider(
                    context = context,
                    providerType = detectedProviderType,
                    credentials = mapOf(
                        "apiKey" to apiKey,
                        "secretKey" to secretKeyValue,
                        "region" to regionValue
                    )
                )
                
                if (newProvider != null) {
                    // 重新初始化NotificationManager
                    val initSuccess = notificationManager.initialize(newProvider, notificationConfig)
                    if (initSuccess) {
                        Log.i(TAG, "NotificationManager重新初始化成功")
                        
                        // 重新连接WebSocket（方案1+4：优先使用deployResult中的userId，防御性重试）
                        var userId = deployResult.userId
                        
                        if (userId == null) {
                            Log.w(TAG, "deployResult中没有userId，尝试从配置中获取")
                            userId = pushServiceInfo.metadata["userId"] as? String
                            
                            if (userId == null) {
                                Log.w(TAG, "首次从配置获取userId失败，强制重新加载配置")
                                kotlinx.coroutines.delay(100L)
                                notificationConfigManager.reloadConfig()
                                kotlinx.coroutines.delay(100L)
                                val reloadedConfig = notificationConfigManager.getLocalConfig()
                                userId = reloadedConfig?.pushServiceInfo?.metadata?.get("userId") as? String
                                
                                if (userId == null) {
                                    Log.e(TAG, "重新加载后仍无法获取userId")
                                    val config = notificationConfigManager.getLocalConfig()
                                    Log.e(TAG, "诊断信息:")
                                    Log.e(TAG, "  - webhookUrl: ${config?.webhookUrl}")
                                    Log.e(TAG, "  - pushServiceInfo.endpoint: ${config?.pushServiceInfo?.endpoint}")
                                    Log.e(TAG, "  - pushServiceInfo.metadata: ${config?.pushServiceInfo?.metadata}")
                                    Log.e(TAG, "  - deployResult.webhookUrl: ${deployResult.webhookUrl}")
                                    Log.e(TAG, "  - deployResult.userId: ${deployResult.userId}")
                                    Log.w(TAG, "无法获取userId，跳过WebSocket连接")
                                }
                            }
                        }
                        
                        if (userId != null && userId.isNotEmpty()) {
                            Log.i(TAG, "准备连接WebSocket: userId=$userId (来源: ${if (deployResult.userId != null) "deployResult" else "config"})")
                            val connectResult = notificationManager.connect(userId) { notification ->
                                Log.d(TAG, "收到推送通知: ${notification.type}")
                            }
                            if (connectResult.success) {
                                Log.i(TAG, "WebSocket连接成功")
                            } else {
                                Log.w(TAG, "WebSocket连接失败: ${connectResult.errorMessage}")
                            }
                        }
                    } else {
                        Log.w(TAG, "NotificationManager重新初始化失败")
                    }
                } else {
                    Log.w(TAG, "创建新的NotificationProvider失败，无法重新连接")
                }
            } catch (e: Exception) {
                Log.e(TAG, "重新连接WebSocket异常（不影响部署成功）", e)
            }
            
            DeploymentExecutionResult(
                success = true,
                config = notificationConfig
            )
            
        } catch (e: Exception) {
            val errorMsg = e.message ?: "未知异常"
            Log.e(TAG, "部署推送服务异常: ${LogSanitizer.sanitizeThrowable(e)}")
            DeploymentExecutionResult(
                success = false,
                errorMessage = "部署异常",
                errorDetails = "${errorMsg} (${e.javaClass.simpleName})"
            )
        }
    }
    
    /**
     * 更新部署状态
     */
    private fun updateDeploymentState(result: DeploymentExecutionResult) {
        val currentState = _state.value ?: return
        
        _state.value = if (result.success) {
            currentState.copy(
                notificationDeploymentState = NotificationDeploymentState.DEPLOYED,
                notificationConfig = result.config,
                deploymentErrorMessage = null,
                deploymentErrorDetails = null
            )
        } else {
            currentState.copy(
                notificationDeploymentState = NotificationDeploymentState.FAILED,
                deploymentErrorMessage = result.errorMessage,
                deploymentErrorDetails = result.errorDetails
            )
        }
    }
    
    /**
     * 部署验证结果
     */
    private data class DeploymentValidationResult(
        val success: Boolean,
        val errorDetails: String? = null
    )
    
    /**
     * 验证部署配置
     */
    private suspend fun validateDeployment(
        config: org.thoughtcrime.securesms.tap.notification.NotificationConfig
    ): DeploymentValidationResult {
        return try {
            // 验证配置基本有效性
            if (!config.validate()) {
                return DeploymentValidationResult(
                    success = false,
                    errorDetails = "配置验证失败：Webhook URL或推送服务端点无效"
                )
            }
            
            // 验证Webhook URL格式
            if (!config.webhookUrl.startsWith("https://")) {
                return DeploymentValidationResult(
                    success = false,
                    errorDetails = "Webhook URL必须以https://开头"
                )
            }
            
            // 验证推送服务信息
            if (!config.pushServiceInfo.validate()) {
                return DeploymentValidationResult(
                    success = false,
                    errorDetails = "推送服务信息无效：端点或区域为空"
                )
            }
            
            DeploymentValidationResult(success = true)
            
        } catch (e: Exception) {
            DeploymentValidationResult(
                success = false,
                errorDetails = "验证过程异常: ${e.message}"
            )
        }
    }
    
    /**
     * 检查是否需要部署推送服务
     */
    private suspend fun checkNotificationDeployment(): Boolean {
        return try {
            val hasConfig = notificationConfigManager.hasLocalConfig()
            if (!hasConfig) {
                Log.i(TAG, "检测到推送服务未部署，需要部署")
                false
            } else {
                val config = notificationConfigManager.getLocalConfig()
                if (config == null || !config.validate()) {
                    Log.w(TAG, "推送服务配置无效，需要重新部署")
                    false
                } else {
                    Log.d(TAG, "推送服务已部署且配置有效")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查推送服务部署状态失败", e)
            false
        }
    }
} 