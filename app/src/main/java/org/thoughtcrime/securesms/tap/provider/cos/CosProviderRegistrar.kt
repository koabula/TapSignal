package org.thoughtcrime.securesms.tap.provider.cos

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.*

/**
 * COS Provider注册器
 * 
 * 负责COS Provider的注册、实例创建和生命周期管理。
 * 实现了TAP的Provider插件协议。
 */
class CosProviderRegistrar : ProviderRegistrar {

    companion object {
        private val TAG = Log.tag(CosProviderRegistrar::class.java)
    }

    override val providerType: String = "cos"

    override fun getConfigDescriptor(): ProviderConfigDescriptor {
        return CosProviderConfigDescriptor()
    }

    override fun createProvider(config: Map<String, Any>, context: Context): TransportProvider? {
        return try {
            Log.i(TAG, "创建COS Provider实例")
            
            // 验证配置的完整性
            val configDescriptor = getConfigDescriptor()
            val validationResult = configDescriptor.validateConfig(config)
            
            if (validationResult is ConfigValidationResult.Invalid) {
                Log.e(TAG, "配置验证失败: ${validationResult.errors}")
                return null
            }
            
            // 创建COS Provider实例
            val provider = CosTransportProvider(context, config)
            
            Log.i(TAG, "COS Provider实例创建成功")
            provider
            
        } catch (e: Exception) {
            Log.e(TAG, "创建COS Provider实例失败", e)
            null
        }
    }

    override fun validateEnvironment(context: Context): ProviderEnvironmentValidation {
        return try {
            Log.d(TAG, "验证COS Provider运行环境")
            
            val issues = mutableListOf<String>()
            val recommendations = mutableListOf<String>()
            
            // 检查网络权限
            val packageManager = context.packageManager
            val packageName = context.packageName
            
            try {
                val internetPermission = packageManager.checkPermission(
                    android.Manifest.permission.INTERNET, 
                    packageName
                )
                if (internetPermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    issues.add("缺少INTERNET权限")
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查INTERNET权限时发生异常", e)
            }
            
            try {
                val networkStatePermission = packageManager.checkPermission(
                    android.Manifest.permission.ACCESS_NETWORK_STATE, 
                    packageName
                )
                if (networkStatePermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    issues.add("缺少ACCESS_NETWORK_STATE权限")
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查ACCESS_NETWORK_STATE权限时发生异常", e)
            }
            
            // 检查存储权限
            try {
                val writePermission = packageManager.checkPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE, 
                    packageName
                )
                if (writePermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    recommendations.add("建议添加WRITE_EXTERNAL_STORAGE权限以优化临时文件处理")
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查存储权限时发生异常", e)
            }
            
            // 检查缓存目录
            try {
                val cacheDir = context.cacheDir
                if (cacheDir == null || !cacheDir.exists()) {
                    issues.add("应用缓存目录不可用")
                } else if (!cacheDir.canWrite()) {
                    issues.add("应用缓存目录不可写")
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查缓存目录时发生异常", e)
                issues.add("无法访问应用缓存目录")
            }
            
            // 检查网络连接性
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
                    as? android.net.ConnectivityManager
                
                if (connectivityManager != null) {
                    val activeNetwork = connectivityManager.activeNetworkInfo
                    if (activeNetwork == null || !activeNetwork.isConnected) {
                        recommendations.add("当前无网络连接，请确保网络可用")
                    }
                } else {
                    recommendations.add("无法检查网络状态")
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查网络连接时发生异常", e)
                recommendations.add("网络状态检查失败")
            }
            
            when {
                issues.isNotEmpty() -> {
                    Log.w(TAG, "环境验证失败: $issues")
                    ProviderEnvironmentValidation.Invalid(
                        reason = "COS Provider运行环境不满足要求",
                        requirements = issues
                    )
                }
                recommendations.isNotEmpty() -> {
                    Log.i(TAG, "环境验证通过，但有建议: $recommendations")
                    ProviderEnvironmentValidation.Warning(
                        warning = "COS Provider可以运行，但建议优化环境配置",
                        recommendations = recommendations
                    )
                }
                else -> {
                    Log.i(TAG, "环境验证完全通过")
                    ProviderEnvironmentValidation.Valid
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "环境验证时发生异常", e)
            ProviderEnvironmentValidation.Invalid(
                reason = "环境验证过程中发生异常: ${e.message}",
                requirements = listOf("请检查应用权限和系统状态")
            )
        }
    }

    override fun getDependencies(): List<ProviderDependency> {
        return listOf(
            // 网络权限
            ProviderDependency(
                name = "INTERNET",
                type = ProviderDependency.DependencyType.PERMISSION,
                required = true,
                description = "访问网络进行云存储操作"
            ),
            ProviderDependency(
                name = "ACCESS_NETWORK_STATE",
                type = ProviderDependency.DependencyType.PERMISSION,
                required = true,
                description = "检查网络连接状态"
            ),
            
            // 存储权限（可选）
            ProviderDependency(
                name = "WRITE_EXTERNAL_STORAGE",
                type = ProviderDependency.DependencyType.PERMISSION,
                required = false,
                description = "优化临时文件处理性能"
            ),
            
            // 库依赖
            ProviderDependency(
                name = "okhttp3",
                version = "4.9.0+",
                type = ProviderDependency.DependencyType.LIBRARY,
                required = true,
                description = "HTTP客户端库，用于网络请求"
            ),
            ProviderDependency(
                name = "jackson-databind",
                version = "2.13.0+",
                type = ProviderDependency.DependencyType.LIBRARY,
                required = true,
                description = "JSON序列化库"
            ),
            
            // 系统服务
            ProviderDependency(
                name = "ConnectivityManager",
                type = ProviderDependency.DependencyType.SERVICE,
                required = true,
                description = "网络连接管理服务"
            )
        )
    }

    override fun getSupportedFeatures(): Set<ProviderFeature> {
        return setOf(
            ProviderFeature.AUTH_MANAGEMENT,       // 支持权限管理
            ProviderFeature.GROUP_TRANSPORT,       // 支持群组传输
            ProviderFeature.CONFIG_TEST,           // 支持配置测试
            ProviderFeature.LARGE_FILE_SUPPORT,    // 支持大文件传输
            ProviderFeature.COMPRESSION,           // 支持压缩传输
            ProviderFeature.ENCRYPTION,            // 支持加密传输
            ProviderFeature.PROGRESS_CALLBACK,     // 支持进度回调
            ProviderFeature.CUSTOM_METADATA        // 支持自定义元数据
        )
    }

    override fun onProviderRegistered(context: Context) {
        try {
            Log.i(TAG, "COS Provider注册成功")
            
            // 执行注册后的初始化工作
            initializeProvider(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Provider注册后初始化失败", e)
        }
    }

    override fun onProviderUnregistered(context: Context) {
        try {
            Log.i(TAG, "COS Provider注销")
            
            // 执行清理工作
            cleanupProvider(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Provider注销时清理失败", e)
        }
    }
    
    /**
     * 初始化Provider
     */
    private fun initializeProvider(context: Context) {
        try {
            Log.d(TAG, "初始化COS Provider")
            
            // 检查并创建必要的目录
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                val cosWorkDir = java.io.File(cacheDir, "cos_transport")
                if (!cosWorkDir.exists()) {
                    cosWorkDir.mkdirs()
                    Log.d(TAG, "创建COS工作目录: ${cosWorkDir.absolutePath}")
                }
            }
            
            // 初始化性能监控
            initializePerformanceMonitoring()
            
            Log.i(TAG, "COS Provider初始化完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化COS Provider时发生异常", e)
        }
    }
    
    /**
     * 清理Provider资源
     */
    private fun cleanupProvider(context: Context) {
        try {
            Log.d(TAG, "清理COS Provider资源")
            
            // 清理临时文件
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                val cosWorkDir = java.io.File(cacheDir, "cos_transport")
                if (cosWorkDir.exists()) {
                    val tempFiles = cosWorkDir.listFiles { file ->
                        file.name.startsWith("cos_upload_") || 
                        file.name.startsWith("cos_download_") ||
                        file.name.startsWith("tap_test")
                    }
                    
                    tempFiles?.forEach { file ->
                        try {
                            if (file.delete()) {
                                Log.d(TAG, "删除临时文件: ${file.name}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "删除临时文件失败: ${file.name}", e)
                        }
                    }
                }
            }
            
            // 停止性能监控
            cleanupPerformanceMonitoring()
            
            Log.i(TAG, "COS Provider资源清理完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "清理COS Provider资源时发生异常", e)
        }
    }
    
    /**
     * 初始化性能监控
     */
    private fun initializePerformanceMonitoring() {
        try {
            Log.d(TAG, "初始化COS性能监控")
            // 这里可以初始化性能监控相关的组件
            // 例如：统计上传下载速度、成功率等
        } catch (e: Exception) {
            Log.w(TAG, "初始化性能监控失败", e)
        }
    }
    
    /**
     * 清理性能监控
     */
    private fun cleanupPerformanceMonitoring() {
        try {
            Log.d(TAG, "清理COS性能监控")
            // 清理性能监控相关的资源
        } catch (e: Exception) {
            Log.w(TAG, "清理性能监控失败", e)
        }
    }
} 