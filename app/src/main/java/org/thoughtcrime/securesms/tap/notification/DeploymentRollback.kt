package org.thoughtcrime.securesms.tap.notification

import android.content.Context
import org.signal.core.util.logging.Log

/**
 * 部署回滚管理器
 * 
 * 负责在部署失败时清理已创建的资源
 */
class DeploymentRollback(
    private val context: Context,
    private val provider: String
) {
    
    companion object {
        private val TAG = Log.tag(DeploymentRollback::class.java)
    }
    
    private val tracker = DeploymentTracker(context, provider)
    
    data class RollbackResult(
        val success: Boolean,
        val deletedResources: List<String>,
        val failedResources: List<String>,
        val errors: List<String>
    )
    
    suspend fun rollback(deployer: NotificationDeployer): RollbackResult {
        Log.i(TAG, "Starting rollback for provider: $provider")
        
        val deletedResources = mutableListOf<String>()
        val failedResources = mutableListOf<String>()
        val errors = mutableListOf<String>()
        
        val resources = tracker.getCreatedResources()
        Log.d(TAG, "Found ${resources.size} resources to clean up")
        
        // Reverse order to delete dependencies first
        for (resource in resources.reversed()) {
            try {
                Log.d(TAG, "Attempting to delete resource: ${resource.type} - ${resource.name}")
                
                val deleted = when (resource.type) {
                    DeploymentTracker.ResourceType.LAMBDA_FUNCTION,
                    DeploymentTracker.ResourceType.CLOUD_FUNCTION -> {
                        deleteFunction(resource, deployer)
                    }
                    DeploymentTracker.ResourceType.API_GATEWAY,
                    DeploymentTracker.ResourceType.WEBSOCKET_API -> {
                        deleteApiGateway(resource, deployer)
                    }
                    DeploymentTracker.ResourceType.DYNAMODB_TABLE -> {
                        deleteDynamoDBTable(resource, deployer)
                    }
                    DeploymentTracker.ResourceType.IAM_ROLE,
                    DeploymentTracker.ResourceType.CAM_ROLE -> {
                        deleteRole(resource, deployer)
                    }
                    DeploymentTracker.ResourceType.S3_BUCKET,
                    DeploymentTracker.ResourceType.COS_BUCKET -> {
                        // 不删除用户的bucket，只清理我们创建的对象
                        Log.i(TAG, "跳过bucket删除（用户资源）: ${resource.name}")
                        true
                    }
                    DeploymentTracker.ResourceType.FUNCTION_URL,
                    DeploymentTracker.ResourceType.HTTP_TRIGGER -> {
                        // 这些资源会随函数自动删除
                        Log.d(TAG, "跳过URL/触发器删除（随函数自动删除）: ${resource.name}")
                        true
                    }
                }
                
                if (deleted) {
                    deletedResources.add("${resource.type}: ${resource.name}")
                    Log.i(TAG, "Successfully deleted resource: ${resource.name}")
                } else {
                    failedResources.add("${resource.type}: ${resource.name}")
                    Log.w(TAG, "Failed to delete resource: ${resource.name}")
                }
                
            } catch (e: Exception) {
                val errorMsg = "Failed to delete ${resource.type} ${resource.name}: ${e.message}"
                errors.add(errorMsg)
                failedResources.add("${resource.type}: ${resource.name}")
                Log.e(TAG, errorMsg, e)
            }
        }
        
        // Clear deployment state if rollback was successful
        if (failedResources.isEmpty()) {
            tracker.clearDeploymentState()
            Log.i(TAG, "Rollback completed successfully, deployment state cleared")
        } else {
            Log.w(TAG, "Rollback completed with ${failedResources.size} failures")
        }
        
        return RollbackResult(
            success = failedResources.isEmpty(),
            deletedResources = deletedResources,
            failedResources = failedResources,
            errors = errors
        )
    }
    
    private suspend fun deleteFunction(resource: DeploymentTracker.CreatedResource, deployer: NotificationDeployer): Boolean {
        return try {
            Log.i(TAG, "删除云函数: ${resource.name}, identifier=${resource.identifier}")
            
            // 调用Deployer的删除方法
            val deleted = deployer.deleteFunction(resource.identifier, resource.name)
            
            if (deleted) {
                Log.i(TAG, "云函数删除成功: ${resource.name}")
            } else {
                Log.w(TAG, "云函数删除失败或不存在: ${resource.name}")
            }
            
            deleted
            
        } catch (e: Exception) {
            Log.e(TAG, "删除云函数异常: ${resource.name}", e)
            false
        }
    }
    
    private suspend fun deleteApiGateway(resource: DeploymentTracker.CreatedResource, deployer: NotificationDeployer): Boolean {
        return try {
            Log.i(TAG, "删除API Gateway: ${resource.name}, identifier=${resource.identifier}")
            
            val deleted = deployer.deleteApiGateway(resource.identifier, resource.name)
            
            if (deleted) {
                Log.i(TAG, "API Gateway删除成功: ${resource.name}")
            } else {
                Log.w(TAG, "API Gateway删除失败或不存在: ${resource.name}")
            }
            
            deleted
            
        } catch (e: Exception) {
            Log.e(TAG, "删除API Gateway异常: ${resource.name}", e)
            false
        }
    }
    
    private suspend fun deleteDynamoDBTable(resource: DeploymentTracker.CreatedResource, deployer: NotificationDeployer): Boolean {
        return try {
            Log.i(TAG, "删除DynamoDB表/云数据库: ${resource.name}, identifier=${resource.identifier}")
            
            val deleted = deployer.deleteDynamoDBTable(resource.identifier, resource.name)
            
            if (deleted) {
                Log.i(TAG, "DynamoDB表/云数据库删除成功: ${resource.name}")
            } else {
                Log.w(TAG, "DynamoDB表/云数据库删除失败或不存在: ${resource.name}")
            }
            
            deleted
            
        } catch (e: Exception) {
            Log.e(TAG, "删除DynamoDB表/云数据库异常: ${resource.name}", e)
            false
        }
    }
    
    private suspend fun deleteRole(resource: DeploymentTracker.CreatedResource, deployer: NotificationDeployer): Boolean {
        return try {
            Log.i(TAG, "删除IAM/CAM角色: ${resource.name}, identifier=${resource.identifier}")
            
            val deleted = deployer.deleteRole(resource.identifier, resource.name)
            
            if (deleted) {
                Log.i(TAG, "角色删除成功: ${resource.name}")
            } else {
                Log.w(TAG, "角色删除失败或不存在: ${resource.name}")
            }
            
            deleted
            
        } catch (e: Exception) {
            Log.e(TAG, "删除角色异常: ${resource.name}", e)
            false
        }
    }
}

