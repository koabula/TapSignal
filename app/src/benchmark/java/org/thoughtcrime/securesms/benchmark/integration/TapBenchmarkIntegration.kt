package org.thoughtcrime.securesms.benchmark.integration

import org.thoughtcrime.securesms.benchmark.BenchmarkRecorder
import org.thoughtcrime.securesms.benchmark.BenchmarkStage
import org.thoughtcrime.securesms.benchmark.MessageType

/**
 * Integration helper to add benchmark instrumentation to TAP components.
 * 
 * This file provides examples of how to integrate benchmark recording
 * into the existing TAP infrastructure without polluting the main codebase.
 */
object TapBenchmarkIntegration {
  
  /**
   * Wraps encryption operation with benchmark recording
   */
  inline fun <T> recordEncryption(messageId: String, block: () -> T): T {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.ENCRYPT_START)
    try {
      return block()
    } finally {
      BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.ENCRYPT_END)
    }
  }
  
  /**
   * Wraps upload operation with benchmark recording
   */
  inline fun <T> recordUpload(messageId: String, block: () -> T): T {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UPLOAD_START)
    try {
      return block()
    } finally {
      BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UPLOAD_END)
    }
  }
  
  /**
   * Wraps download operation with benchmark recording
   */
  inline fun <T> recordDownload(messageId: String, block: () -> T): T {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.DOWNLOAD_START)
    try {
      return block()
    } finally {
      BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.DOWNLOAD_END)
    }
  }
  
  /**
   * Wraps decryption operation with benchmark recording
   */
  inline fun <T> recordDecryption(messageId: String, block: () -> T): T {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.DECRYPT_START)
    try {
      return block()
    } finally {
      BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.DECRYPT_END)
    }
  }
  
  /**
   * Records that polling detected a new message
   */
  fun recordPollDetection(messageId: String) {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.POLL_DETECTED)
  }
  
  /**
   * Records that a message was displayed in the UI
   */
  fun recordUiDisplay(messageId: String) {
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.UI_DISPLAYED)
  }
  
  /**
   * Records the send button click
   */
  fun recordSendClick(messageId: String, messageType: MessageType, sizeBytes: Long) {
    if (!BenchmarkRecorder.isEnabled()) return
    
    BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.SEND_CLICKED)
    
    // Store message metadata
    val record = BenchmarkRecorder.getRecord(messageId)
    record?.apply {
      this.messageType = messageType
      this.messageSizeBytes = sizeBytes
    }
  }
}

