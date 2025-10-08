package org.whispersystems.signalservice.api;

import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * TAP 消息传输接口
 * 用于将加密后的消息通过自定义传输层（如 COS、NAS 等）发送，而不是通过 Signal Server
 */
public interface TapMessageTransport {
  
  /**
   * 检查指定的群组是否应该使用 TAP 传输
   * 
   * @param groupId 群组 ID（Base64 编码）
   * @return 如果群组处于 v2 mode 且应该使用 TAP 传输，返回 true
   */
  boolean shouldUseTapForGroup(Optional<byte[]> groupId);
  
  /**
   * 检查指定的收件人是否应该使用 TAP 传输（用于私聊）
   * 
   * @param recipient 收件人地址
   * @return 如果该对话处于 v2 mode 且应该使用 TAP 传输，返回 true
   */
  boolean shouldUseTapForRecipient(SignalServiceAddress recipient);
  
  /**
   * 通过 TAP 传输层发送群组消息
   * 
   * @param groupId 群组 ID
   * @param recipients 收件人列表
   * @param ciphertext 加密后的消息内容（由 Signal 加密）
   * @param timestamp 消息时间戳
   * @param urgent 是否为紧急消息
   * @param online 是否为在线消息
   * @return 每个收件人的发送结果
   */
  List<SendMessageResult> sendGroupMessageViaTap(
      Optional<byte[]> groupId,
      List<SignalServiceAddress> recipients,
      byte[] ciphertext,
      long timestamp,
      boolean urgent,
      boolean online
  ) throws IOException;
  
  /**
   * 通过 TAP 传输层发送私聊消息
   * 
   * @param recipient 收件人地址
   * @param ciphertext 加密后的消息内容（由 Signal 加密）
   * @param timestamp 消息时间戳
   * @param urgent 是否为紧急消息
   * @param online 是否为在线消息
   * @return 发送结果
   */
  SendMessageResult sendMessageViaTap(
      SignalServiceAddress recipient,
      byte[] ciphertext,
      long timestamp,
      boolean urgent,
      boolean online
  ) throws IOException;
}

