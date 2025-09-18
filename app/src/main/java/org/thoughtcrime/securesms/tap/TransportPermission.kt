package org.thoughtcrime.securesms.tap

/**
 * 传输权限枚举
 * 
 * 定义了传输服务支持的各种权限类型，用于Token权限管理和访问控制。
 * 不同的传输服务可以根据自己的特性支持不同的权限组合。
 */
enum class TransportPermission(
    val displayName: String,
    val description: String
) {
    /** 读取权限 - 允许下载和查看文件 */
    READ("读取", "允许下载和查看文件"),
    
    /** 写入权限 - 允许上传和修改文件 */
    WRITE("写入", "允许上传和修改文件"),
    
    /** 删除权限 - 允许删除文件 */
    DELETE("删除", "允许删除文件"),
    
    /** 列表权限 - 允许列出目录内容 */
    LIST("列表", "允许列出目录内容");
    
    companion object {
        /**
         * 获取所有权限的集合
         */
        fun allPermissions(): Set<TransportPermission> = values().toSet()
        
        /**
         * 获取只读权限集合（READ + LIST）
         */
        fun readOnlyPermissions(): Set<TransportPermission> = setOf(READ, LIST)
        
        /**
         * 获取读写权限集合（READ + WRITE + LIST）
         */
        fun readWritePermissions(): Set<TransportPermission> = setOf(READ, WRITE, LIST)
        
        /**
         * 获取完整权限集合（包含所有权限）
         */
        fun fullPermissions(): Set<TransportPermission> = allPermissions()
        
        /**
         * 从字符串列表解析权限集合
         */
        fun fromStringList(permissions: List<String>): Set<TransportPermission> {
            return permissions.mapNotNull { permissionStr ->
                try {
                    valueOf(permissionStr.uppercase())
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet()
        }
        
        /**
         * 将权限集合转换为字符串列表
         */
        fun toStringList(permissions: Set<TransportPermission>): List<String> {
            return permissions.map { it.name }.sorted()
        }
        
        /**
         * 检查权限集合是否包含特定权限
         */
        fun hasPermission(permissions: Set<TransportPermission>, permission: TransportPermission): Boolean {
            return permissions.contains(permission)
        }
        
        /**
         * 检查权限集合是否足够执行指定操作
         */
        fun canPerformOperation(permissions: Set<TransportPermission>, operation: TransportOperation): Boolean {
            return when (operation) {
                TransportOperation.PUSH -> permissions.contains(WRITE)
                TransportOperation.PULL -> permissions.contains(READ)
                TransportOperation.DELETE -> permissions.contains(DELETE)
                TransportOperation.LIST -> permissions.contains(LIST)
                TransportOperation.READ_ONLY -> permissions.containsAll(readOnlyPermissions())
                TransportOperation.FULL_ACCESS -> permissions.containsAll(fullPermissions())
            }
        }
    }
}

/**
 * 传输操作枚举
 * 
 * 定义了传输服务支持的操作类型，用于权限检查。
 */
enum class TransportOperation(
    val displayName: String,
    val requiredPermissions: Set<TransportPermission>
) {
    /** 推送文件操作 */
    PUSH("推送文件", setOf(TransportPermission.WRITE)),
    
    /** 拉取文件操作 */
    PULL("拉取文件", setOf(TransportPermission.READ)),
    
    /** 删除文件操作 */
    DELETE("删除文件", setOf(TransportPermission.DELETE)),
    
    /** 列表文件操作 */
    LIST("列表文件", setOf(TransportPermission.LIST)),
    
    /** 只读访问操作 */
    READ_ONLY("只读访问", TransportPermission.readOnlyPermissions()),
    
    /** 完整访问操作 */
    FULL_ACCESS("完整访问", TransportPermission.fullPermissions());
    
    /**
     * 检查给定权限是否足够执行此操作
     */
    fun isPermissionSufficient(permissions: Set<TransportPermission>): Boolean {
        return permissions.containsAll(requiredPermissions)
    }
} 