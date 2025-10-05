package org.thoughtcrime.securesms.tap.group.utils

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.BadGroupIdException
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

/**
 * 群组 ID 格式转换工具类
 * 
 * 统一处理不同格式的 groupId，内部统一使用 GroupId 编码字符串格式
 * 支持的输入格式：
 * 1. RecipientId 序列化数字字符串
 * 2. GroupId 编码字符串（如 "__signal_group__v2__!xxxx"）
 * 3. Base64 编码的群组 ID 字节数组
 */
object GroupIdConverter {
    
    private val TAG = Log.tag(GroupIdConverter::class.java)
    
    /**
     * 转换结果
     */
    sealed class ConversionResult {
        data class Success(
            val groupId: GroupId,
            val groupIdString: String,
            val recipientId: RecipientId
        ) : ConversionResult()
        
        data class Failed(val reason: String) : ConversionResult()
    }
    
    /**
     * 将任意格式的 groupId 转换为标准格式
     * 
     * @param input 输入的 groupId（任意支持的格式）
     * @param context 上下文
     * @return 转换结果
     */
    fun convert(input: String, context: Context): ConversionResult {
        return try {
            // 方法1：尝试作为 RecipientId 数字解析
            try {
                val recipientId = RecipientId.from(input.toLong())
                val recipient = Recipient.resolved(recipientId)
                if (recipient.isGroup) {
                    val groupId = recipient.requireGroupId()
                    return ConversionResult.Success(
                        groupId = groupId,
                        groupIdString = groupId.toString(),
                        recipientId = recipientId
                    )
                }
            } catch (e: NumberFormatException) {
                // 不是数字，继续尝试其他方法
            }
            
            // 方法2：尝试作为 GroupId 编码字符串解析
            try {
                val groupId = GroupId.parse(input)
                val recipientIdOptional = SignalDatabase.recipients.getByGroupId(groupId)
                if (recipientIdOptional.isPresent) {
                    return ConversionResult.Success(
                        groupId = groupId,
                        groupIdString = groupId.toString(),
                        recipientId = recipientIdOptional.get()
                    )
                } else {
                    Log.w(TAG, "GroupId 有效但找不到对应 Recipient: $input")
                }
            } catch (e: BadGroupIdException) {
                // 不是有效的 GroupId 编码，继续尝试其他方法
            }
            
            // 方法3：尝试作为 Base64 编码的字节数组解析
            try {
                val groupIdBytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
                val groupId = GroupId.push(groupIdBytes)
                val recipientIdOptional = SignalDatabase.recipients.getByGroupId(groupId)
                if (recipientIdOptional.isPresent) {
                    return ConversionResult.Success(
                        groupId = groupId,
                        groupIdString = groupId.toString(),
                        recipientId = recipientIdOptional.get()
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "无法从 Base64 解析 GroupId: $input")
            }
            
            // 所有方法都失败
            ConversionResult.Failed("无法识别的 groupId 格式: $input")
            
        } catch (e: Exception) {
            Log.e(TAG, "转换 groupId 失败: $input", e)
            ConversionResult.Failed("转换异常: ${e.message}")
        }
    }
    
    /**
     * 从 RecipientId 获取标准 groupId 字符串
     */
    fun fromRecipientId(recipientId: RecipientId): String? {
        return try {
            val recipient = Recipient.resolved(recipientId)
            if (recipient.isGroup) {
                recipient.requireGroupId().toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "从 RecipientId 获取 groupId 失败: $recipientId", e)
            null
        }
    }
    
    /**
     * 从标准 groupId 字符串获取 RecipientId
     */
    fun toRecipientId(groupIdString: String): RecipientId? {
        return try {
            val groupId = GroupId.parse(groupIdString)
            val recipientIdOptional = SignalDatabase.recipients.getByGroupId(groupId)
            if (recipientIdOptional.isPresent) {
                recipientIdOptional.get()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "从 groupId 获取 RecipientId 失败: $groupIdString", e)
            null
        }
    }
    
    /**
     * 验证 groupId 格式是否有效
     */
    fun isValid(input: String): Boolean {
        return try {
            GroupId.parse(input)
            true
        } catch (e: BadGroupIdException) {
            false
        }
    }
    
    /**
     * 批量转换
     */
    fun convertBatch(inputs: List<String>, context: Context): Map<String, ConversionResult> {
        return inputs.associateWith { convert(it, context) }
    }
}
