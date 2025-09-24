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
    val isLoading: Boolean = false
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

    /**
     * 初始化ViewModel
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        this.configManager = TransportProviderConfigManager.getInstance(context)
        this.providerManager = TransportProviderManager.getInstance(context)
        this.providerRegistry = ProviderRegistry.getInstance(context)
        
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
                        isLoading = false
                    )
                } else {
                    // 没有现有配置，显示Provider选择
                    _state.value = TapConfigState(
                        availableProviders = availableProviders,
                        isLoading = false
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

                _state.value = currentState.copy(
                    selectedProviderType = providerType,
                    currentProviderDescriptor = descriptor,
                    configValues = emptyMap(),
                    isConfigValid = false,
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
                val testResult = descriptor.testConfig(currentState.configValues)

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
} 