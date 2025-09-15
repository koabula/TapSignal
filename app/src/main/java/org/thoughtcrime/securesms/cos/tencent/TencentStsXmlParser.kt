package org.thoughtcrime.securesms.cos.tencent

import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.cos.CosAccessToken
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 腾讯云STS返回XML解析器
 */
object TencentStsXmlParser {
    private val TAG = Log.tag(TencentStsXmlParser::class.java)
    
    /**
     * 解析联合身份临时访问凭证响应
     */
    fun parseFederationToken(xmlResponse: String): CosAccessToken {
        try {
            Log.d(TAG, "开始解析XML格式STS响应")
            
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(org.xml.sax.InputSource(StringReader(xmlResponse)))

            val root = doc.documentElement
            
            // 检查是否有错误
            val errorNode = getElementByTagName(doc, "Error")
            if (errorNode != null) {
                val errorCode = getTextContent(errorNode, "Code")
                val errorMessage = getTextContent(errorNode, "Message")
                Log.e(TAG, "STS返回错误: $errorCode - $errorMessage")
                throw IllegalStateException("STS返回错误: $errorCode - $errorMessage")
            }
            
            // 提取临时访问凭证
            val credentials = getElementByTagName(doc, "Credentials")
                ?: throw IllegalStateException("无法找到临时访问凭证信息")
            
            val secretId = getTextContent(credentials, "TmpSecretId") 
                ?: throw IllegalStateException("无法找到临时SecretId")
            val secretKey = getTextContent(credentials, "TmpSecretKey")
                ?: throw IllegalStateException("无法找到临时SecretKey")
            val token = getTextContent(credentials, "Token")
                ?: throw IllegalStateException("无法找到临时令牌")
            val expiredTimeStr = getTextContent(credentials, "ExpiredTime")
                ?: throw IllegalStateException("无法找到过期时间")
            
            // 转换过期时间为毫秒时间戳
            val expiredTime = try {
                expiredTimeStr.toLong() * 1000 // 转换为毫秒
            } catch (e: NumberFormatException) {
                Log.e(TAG, "解析过期时间失败: $expiredTimeStr", e)
                System.currentTimeMillis() + 3600 * 1000 // 默认1小时
            }
            
            Log.d(TAG, "成功解析临时访问凭证，过期时间: $expiredTimeStr")
            
            return CosAccessToken(
                accessKeyId = secretId,
                secretAccessKey = secretKey,
                sessionToken = token,
                expireTime = expiredTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析STS响应失败", e)
            throw IllegalStateException("解析STS XML响应失败: ${e.message}")
        }
    }
    
    /**
     * 解析腾讯云STS返回的JSON格式响应
     */
    fun parseJsonResponse(jsonResponse: String): CosAccessToken {
        try {
            Log.d(TAG, "开始解析JSON格式STS响应")
            
            val json = JSONObject(jsonResponse)
            
            // 检查是否有错误
            if (json.has("Response") && json.getJSONObject("Response").has("Error")) {
                val error = json.getJSONObject("Response").getJSONObject("Error")
                val errorCode = error.optString("Code", "未知错误码")
                val errorMessage = error.optString("Message", "未知错误")
                val requestId = json.getJSONObject("Response").optString("RequestId", "")
                
                // 增加详细的错误日志
                Log.e(TAG, "STS API返回错误: 错误码=$errorCode, 错误信息=$errorMessage, 请求ID=$requestId")
                
                // 根据不同错误码提供更具体的错误信息
                val detailedMessage = when (errorCode) {
                    "InvalidParameter.GrantOtherResource" -> 
                        "CAM策略错误: 尝试授予对无权访问的资源的权限，请检查资源路径格式是否正确"
                    "InvalidParameter" -> 
                        "参数错误: 请检查传递给STS服务的所有参数"
                    "AuthFailure.SignatureFailure" -> 
                        "签名错误: 请检查您的SecretId和SecretKey是否正确"
                    else -> "$errorCode: $errorMessage"
                }
                
                throw IllegalStateException("STS API错误: $detailedMessage, 原始响应: $jsonResponse")
            }
            
            // 确保Response存在
            if (!json.has("Response")) {
                Log.e(TAG, "STS响应缺少Response字段: $jsonResponse")
                throw IllegalStateException("无效的STS JSON响应: 缺少Response字段")
            }
            
            val response = json.getJSONObject("Response")
            
            // 解析临时凭证
            if (!response.has("Credentials")) {
                Log.e(TAG, "STS响应缺少Credentials字段: $jsonResponse")
                throw IllegalStateException("无效的STS JSON响应: 缺少凭证数据. 响应: $jsonResponse")
            }
            
            val credentials = response.getJSONObject("Credentials")
            
            // 提取必要字段
            val secretId = credentials.optString("TmpSecretId")
            val secretKey = credentials.optString("TmpSecretKey")
            val token = credentials.optString("Token")
            val expiredTimeStr = credentials.optString("ExpiredTime")
            
            // 验证字段是否存在
            if (secretId.isBlank() || secretKey.isBlank() || token.isBlank()) {
                Log.e(TAG, "STS响应缺少必要的凭证字段: SecretId=${secretId.isNotBlank()}, SecretKey=${secretKey.isNotBlank()}, Token=${token.isNotBlank()}")
                throw IllegalStateException("无效的Tencent STS JSON: 缺少凭证数据. 响应: $jsonResponse")
            }
            
            // 解析过期时间
            val expiredTime = try {
                expiredTimeStr.toLong() * 1000 // 转换为毫秒
            } catch (e: NumberFormatException) {
                Log.e(TAG, "解析过期时间失败: $expiredTimeStr", e)
                System.currentTimeMillis() + 3600 * 1000 // 默认1小时
            }
            
            Log.d(TAG, "成功解析临时访问凭证，过期时间: $expiredTimeStr")
            
            return CosAccessToken(
                accessKeyId = secretId,
                secretAccessKey = secretKey,
                sessionToken = token,
                expireTime = expiredTime
            )
        } catch (e: Exception) {
            when (e) {
                is IllegalStateException -> throw e  // 已格式化的错误，直接抛出
                else -> {
                    Log.e(TAG, "解析STS JSON响应异常", e)
                    throw IllegalStateException("解析STS JSON响应失败: ${e.message}. 响应: $jsonResponse")
                }
            }
        }
    }
    
    /**
     * 辅助方法：根据标签名称获取第一个元素
     */
    private fun getElementByTagName(doc: Document, tagName: String): Element? {
        val elements = doc.getElementsByTagName(tagName)
        if (elements.length > 0) {
            return elements.item(0) as Element
        }
        return null
    }
    
    /**
     * 辅助方法：从元素中获取文本内容
     */
    private fun getTextContent(parent: Element, tagName: String): String? {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            val element = nodes.item(0) as Element
            return element.textContent.trim()
        }
        return null
    }
}
