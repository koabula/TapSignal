package org.thoughtcrime.securesms.components.settings.app.cos

import android.content.Context
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.cos.CosConfig
import org.thoughtcrime.securesms.cos.CosConfigStorage

class CosSettingsViewModel : ViewModel() {
    private val TAG = Log.tag(CosSettingsViewModel::class.java)

    private val _state = MutableLiveData(CosSettingsState())
    val state: LiveData<CosSettingsState> = _state

    /**
     * 从存储加载当前配置
     */
    fun loadCurrentConfig(context: Context) {
        val config = CosConfigStorage.getConfig(context)

        if (config != null) {
            _state.value = CosSettingsState(
                provider = config.provider,
                secretId = config.secretId,
                secretKey = config.secretKey,
                region = config.region,
                bucketName = config.bucketName
            )
        }
    }

    /**
     * 保存配置到存储
     */
    fun saveConfig(context: Context): Boolean {
        val currentState = _state.value ?: return false

        // 验证必填字段
        if (currentState.secretId.isBlank() || 
            currentState.secretKey.isBlank() || 
            currentState.region.isBlank() || 
            currentState.bucketName.isBlank()) {
            return false
        }

        val config = CosConfig(
            provider = currentState.provider,
            secretId = currentState.secretId,
            secretKey = currentState.secretKey,
            region = currentState.region,
            bucketName = currentState.bucketName
        )

        return CosConfigStorage.saveConfig(context, config)
    }

    /**
     * 设置服务提供商
     */
    fun setProvider(provider: CosConfig.Provider) {
        _state.value = _state.value?.copy(provider = provider)
    }

    /**
     * 更新Secret ID
     */
    fun updateSecretId(value: String) {
        _state.value = _state.value?.copy(secretId = value)
    }

    /**
     * 更新Secret Key
     */
    fun updateSecretKey(value: String) {
        _state.value = _state.value?.copy(secretKey = value)
    }

    /**
     * 更新Region
     */
    fun updateRegion(value: String) {
        _state.value = _state.value?.copy(region = value)
    }

    /**
     * 更新Bucket名称
     */
    fun updateBucketName(value: String) {
        _state.value = _state.value?.copy(bucketName = value)
    }


} 