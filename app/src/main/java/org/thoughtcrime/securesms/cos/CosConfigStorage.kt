package org.thoughtcrime.securesms.cos

import android.content.Context
import androidx.core.content.edit
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * 负责COS配置的持久化存储和读取。
 */
object CosConfigStorage {
    private val TAG = Log.tag(CosConfigStorage::class.java)
    private const val PREFS_NAME = "cos_config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_SECRET_ID = "secret_id"
    private const val KEY_SECRET_KEY = "secret_key"
    private const val KEY_REGION = "region"
    private const val KEY_BUCKET_NAME = "bucket_name"
    private const val KEY_CREDENTIAL_TYPE = "credential_type"
    // 注意：移除了KEY_CAM_DURATION，现在使用长期CAM凭证

    /**
     * 保存COS配置到SharedPreferences
     * 注意：现在使用长期CAM凭证，不再需要设置过期时间
     */
    fun saveConfig(context: Context, config: CosConfig): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_PROVIDER, config.provider.name)
                putString(KEY_SECRET_ID, config.secretId)
                putString(KEY_SECRET_KEY, config.secretKey)
                putString(KEY_REGION, config.region)
                putString(KEY_BUCKET_NAME, config.bucketName)
                // 移除了CAM时长存储，现在使用长期凭证
            }
            Log.i(TAG, "COS config saved successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save COS config", e)
            false
        }
    }

    /**
     * 从SharedPreferences读取COS配置
     */
    fun getConfig(context: Context): CosConfig? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            if (!prefs.contains(KEY_SECRET_ID)) {
                return null
            }
            
            val providerStr = prefs.getString(KEY_PROVIDER, CosConfig.Provider.AWS.name)
            val provider = try {
                CosConfig.Provider.valueOf(providerStr ?: CosConfig.Provider.AWS.name)
            } catch (e: Exception) {
                CosConfig.Provider.AWS
            }
            
            CosConfig(
                provider = provider,
                secretId = prefs.getString(KEY_SECRET_ID, "") ?: "",
                secretKey = prefs.getString(KEY_SECRET_KEY, "") ?: "",
                region = prefs.getString(KEY_REGION, "") ?: "",
                bucketName = prefs.getString(KEY_BUCKET_NAME, "") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get COS config", e)
            null
        }
    }

    /**
     * 获取CAM有效期（分钟）
     * @deprecated 现在使用长期CAM凭证，此方法已废弃
     * @return 返回-1表示永久有效
     */
    @Deprecated("现在使用长期CAM凭证，不再需要设置过期时间")
    fun getCamDuration(context: Context): Int {
        // 返回-1表示永久有效的CAM凭证
        return -1
    }

    /**
     * 保存凭证类型偏好
     */
    fun saveCredentialType(context: Context, credentialType: CosCredentialType): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CREDENTIAL_TYPE, credentialType.name)
                .apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存凭证类型失败", e)
            false
        }
    }

    /**
     * 获取凭证类型偏好
     */
    fun getCredentialType(context: Context): CosCredentialType {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 🔧 修改默认值为PERMANENT，优先使用永久凭证
            val typeString = prefs.getString(KEY_CREDENTIAL_TYPE, CosCredentialType.PERMANENT.name)
            CosCredentialType.valueOf(typeString ?: CosCredentialType.PERMANENT.name)
        } catch (e: Exception) {
            Log.e(TAG, "获取凭证类型失败，使用永久凭证作为默认值", e)
            CosCredentialType.PERMANENT
        }
    }

    /**
     * 检查是否启用永久凭证
     */
    fun isPermanentCredentialEnabled(context: Context): Boolean {
        val credentialType = getCredentialType(context)
        return credentialType == CosCredentialType.PERMANENT ||
               credentialType == CosCredentialType.AUTO
    }
} 