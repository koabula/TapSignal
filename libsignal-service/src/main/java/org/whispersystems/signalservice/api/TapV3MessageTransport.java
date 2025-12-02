package org.whispersystems.signalservice.api;

import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;

/**
 * Tap v3 message transport interface.
 * Used to send encrypted messages via UnifiedPush + IPFS instead of Signal Server.
 */
public interface TapV3MessageTransport {
  
  /**
   * Check if the specified recipient should use Tap v3 transport.
   * 
   * @param recipient The recipient address
   * @return true if Tap v3 channel is established and active for this recipient
   */
  boolean shouldUseTapV3ForRecipient(SignalServiceAddress recipient);
  
  /**
   * Send an encrypted message via Tap v3 transport.
   * This method handles both inline messages and IPFS-based messages.
   * 
   * @param recipient The recipient address
   * @param ciphertext The Signal-encrypted message content
   * @param timestamp Message timestamp
   * @param urgent Whether this is an urgent message
   * @param online Whether this is an online message
   * @return Send result
   */
  SendMessageResult sendMessageViaTapV3(
      SignalServiceAddress recipient,
      byte[] ciphertext,
      long timestamp,
      boolean urgent,
      boolean online
  ) throws IOException;
}
