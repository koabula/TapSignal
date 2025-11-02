package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import android.content.SharedPreferences
import org.signal.core.util.logging.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 部署状态追踪器
 * 
 * 跟踪部署进度，支持回滚和恢复
 */
class DeploymentTracker(
    private val context: Context,
    private val provider: String
) {
    
    companion object {
        private val TAG = Log.tag(DeploymentTracker::class.java)
        private const val PREF_NAME = "tap_notification_deployment"
        private const val KEY_DEPLOYMENT_STATE = "deployment_state"
        private const val KEY_CHECKPOINTS = "checkpoints"
        private const val KEY_CREATED_RESOURCES = "created_resources"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "${PREF_NAME}_$provider",
        Context.MODE_PRIVATE
    )
    
    data class DeploymentCheckpoint(
        val step: DeploymentStep,
        val timestamp: Long,
        val success: Boolean,
        val data: Map<String, String> = emptyMap(),
        val errorMessage: String? = null
    )
    
    data class CreatedResource(
        val type: ResourceType,
        val identifier: String,
        val name: String,
        val data: Map<String, String> = emptyMap()
    )
    
    enum class DeploymentStep(val order: Int, val description: String) {
        NOT_STARTED(0, "未开始"),
        DEPLOYING_WEBHOOK(1, "部署Webhook函数"),
        WEBHOOK_DEPLOYED(2, "Webhook部署完成"),
        DEPLOYING_API_GATEWAY(3, "部署API Gateway服务"),
        API_GATEWAY_DEPLOYED(4, "API Gateway服务部署完成"),
        DEPLOYING_TRIGGER(5, "部署触发器函数"),
        TRIGGER_DEPLOYED(6, "触发器部署完成"),
        CONFIGURING(7, "配置服务"),
        CONFIGURED(8, "配置完成"),
        DEPLOYED(9, "部署成功")
    }
    
    enum class ResourceType {
        LAMBDA_FUNCTION,
        CLOUD_FUNCTION,
        API_GATEWAY,
        WEBSOCKET_API,
        DYNAMODB_TABLE,
        IAM_ROLE,
        CAM_ROLE,
        S3_BUCKET,
        COS_BUCKET,
        FUNCTION_URL,
        HTTP_TRIGGER
    }
    
    fun startDeployment() {
        prefs.edit().apply {
            putString(KEY_DEPLOYMENT_STATE, DeploymentStep.NOT_STARTED.name)
            putString(KEY_CHECKPOINTS, JSONArray().toString())
            putString(KEY_CREATED_RESOURCES, JSONArray().toString())
            apply()
        }
        Log.i(TAG, "Started deployment tracking for provider: $provider")
    }
    
    fun recordCheckpoint(checkpoint: DeploymentCheckpoint) {
        val checkpointsJson = prefs.getString(KEY_CHECKPOINTS, "[]") ?: "[]"
        val checkpoints = JSONArray(checkpointsJson)
        
        val checkpointJson = JSONObject().apply {
            put("step", checkpoint.step.name)
            put("timestamp", checkpoint.timestamp)
            put("success", checkpoint.success)
            put("data", JSONObject(checkpoint.data))
            checkpoint.errorMessage?.let { put("errorMessage", it) }
        }
        
        checkpoints.put(checkpointJson)
        
        prefs.edit().apply {
            putString(KEY_CHECKPOINTS, checkpoints.toString())
            if (checkpoint.success) {
                putString(KEY_DEPLOYMENT_STATE, checkpoint.step.name)
            }
            apply()
        }
        
        Log.d(TAG, "Recorded checkpoint: ${checkpoint.step.description} - success: ${checkpoint.success}")
    }
    
    fun recordResource(resource: CreatedResource) {
        val resourcesJson = prefs.getString(KEY_CREATED_RESOURCES, "[]") ?: "[]"
        val resources = JSONArray(resourcesJson)
        
        val resourceJson = JSONObject().apply {
            put("type", resource.type.name)
            put("identifier", resource.identifier)
            put("name", resource.name)
            put("data", JSONObject(resource.data))
        }
        
        resources.put(resourceJson)
        
        prefs.edit().apply {
            putString(KEY_CREATED_RESOURCES, resources.toString())
            apply()
        }
        
        Log.d(TAG, "Recorded resource: ${resource.type} - ${resource.name}")
    }
    
    fun getCurrentStep(): DeploymentStep {
        val stateName = prefs.getString(KEY_DEPLOYMENT_STATE, DeploymentStep.NOT_STARTED.name)
            ?: DeploymentStep.NOT_STARTED.name
        return try {
            DeploymentStep.valueOf(stateName)
        } catch (e: Exception) {
            DeploymentStep.NOT_STARTED
        }
    }
    
    fun getCheckpoints(): List<DeploymentCheckpoint> {
        val checkpointsJson = prefs.getString(KEY_CHECKPOINTS, "[]") ?: "[]"
        val checkpoints = JSONArray(checkpointsJson)
        
        return (0 until checkpoints.length()).mapNotNull { i ->
            try {
                val json = checkpoints.getJSONObject(i)
                val dataJson = json.optJSONObject("data") ?: JSONObject()
                val dataMap = mutableMapOf<String, String>()
                dataJson.keys().forEach { key ->
                    dataMap[key] = dataJson.getString(key)
                }
                
                DeploymentCheckpoint(
                    step = DeploymentStep.valueOf(json.getString("step")),
                    timestamp = json.getLong("timestamp"),
                    success = json.getBoolean("success"),
                    data = dataMap,
                    errorMessage = json.optString("errorMessage", null)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse checkpoint", e)
                null
            }
        }
    }
    
    fun getCreatedResources(): List<CreatedResource> {
        val resourcesJson = prefs.getString(KEY_CREATED_RESOURCES, "[]") ?: "[]"
        val resources = JSONArray(resourcesJson)
        
        return (0 until resources.length()).mapNotNull { i ->
            try {
                val json = resources.getJSONObject(i)
                val dataJson = json.optJSONObject("data") ?: JSONObject()
                val dataMap = mutableMapOf<String, String>()
                dataJson.keys().forEach { key ->
                    dataMap[key] = dataJson.getString(key)
                }
                
                CreatedResource(
                    type = ResourceType.valueOf(json.getString("type")),
                    identifier = json.getString("identifier"),
                    name = json.getString("name"),
                    data = dataMap
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse resource", e)
                null
            }
        }
    }
    
    fun isDeploymentInProgress(): Boolean {
        val currentStep = getCurrentStep()
        return currentStep.order > DeploymentStep.NOT_STARTED.order && 
               currentStep.order < DeploymentStep.DEPLOYED.order
    }
    
    fun hasPartialDeployment(): Boolean {
        return getCreatedResources().isNotEmpty() && getCurrentStep() != DeploymentStep.DEPLOYED
    }
    
    fun clearDeploymentState() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Cleared deployment state for provider: $provider")
    }
    
    fun getDeploymentProgress(): Float {
        val currentStep = getCurrentStep()
        return currentStep.order.toFloat() / DeploymentStep.DEPLOYED.order.toFloat()
    }
}

