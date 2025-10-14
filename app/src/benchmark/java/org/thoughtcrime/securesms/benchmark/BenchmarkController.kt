package org.thoughtcrime.securesms.benchmark

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.thoughtcrime.securesms.benchmark.BenchmarkMessageGenerator
import java.io.File

/**
 * Controller for executing benchmark test sessions
 */
class BenchmarkController(
  private val context: Context,
  private val scope: CoroutineScope
) {
  
  private val TAG = "BenchmarkController"
  private var currentJob: Job? = null
  private var currentConfig: BenchmarkConfig? = null
  
  fun startBenchmarkSession(
    config: BenchmarkConfig,
    onProgress: (Int, Int) -> Unit,
    onComplete: (BenchmarkReport) -> Unit,
    sendMessageCallback: (String, File?, MessageType) -> Unit
  ) {
    if (currentJob?.isActive == true) {
      Log.w(TAG, "Benchmark session already running")
      return
    }
    
    currentConfig = config
    BenchmarkRecorder.enable(config)
    
    currentJob = scope.launch {
      try {
        if (config.deviceRole == DeviceRole.SENDER || config.deviceRole == DeviceRole.BOTH) {
          executeSenderSession(config, onProgress, sendMessageCallback)
        }
        
        // Wait for messages to be received and processed
        if (config.deviceRole == DeviceRole.BOTH) {
          Log.i(TAG, "Waiting for messages to be received...")
          delay(config.messageCount * config.intervalMs + 60000) // Extra 60s buffer
        }
        
        // Generate report
        val report = BenchmarkRecorder.generateReport()
        withContext(Dispatchers.Main) {
          onComplete(report)
        }
        
      } catch (e: Exception) {
        Log.e(TAG, "Benchmark session failed", e)
        withContext(Dispatchers.Main) {
          Toast.makeText(context, "Benchmark failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
      } finally {
        BenchmarkRecorder.disable()
        currentJob = null
      }
    }
  }
  
  private suspend fun executeSenderSession(
    config: BenchmarkConfig,
    onProgress: (Int, Int) -> Unit,
    sendMessageCallback: (String, File?, MessageType) -> Unit
  ) {
    Log.i(TAG, "Starting sender session: $config")
    
    for (i in 0 until config.messageCount) {
      val messageId = "benchmark_${config.sessionId}_${i}"
      
      withContext(Dispatchers.Main) {
        onProgress(i + 1, config.messageCount)
      }
      
      // Record T0: Send clicked
      BenchmarkRecorder.recordTimestamp(messageId, BenchmarkStage.SEND_CLICKED)
      
      // Generate and send message based on type
      when (config.messageType) {
        MessageType.TEXT -> {
          val text = BenchmarkMessageGenerator.generateTextMessage(i, config.messageSize)
          withContext(Dispatchers.Main) {
            sendMessageCallback(text, null, MessageType.TEXT)
          }
        }
        MessageType.IMAGE -> {
          val imageFile = BenchmarkMessageGenerator.getImageFile(context, config.messageSize)
          if (imageFile != null) {
            withContext(Dispatchers.Main) {
              sendMessageCallback("Benchmark image #$i", imageFile, MessageType.IMAGE)
            }
          } else {
            Log.e(TAG, "Failed to generate image for message $i")
          }
        }
        MessageType.VOICE -> {
          val voiceFile = BenchmarkMessageGenerator.getVoiceMessageFile(context, config.messageSize)
          if (voiceFile != null) {
            withContext(Dispatchers.Main) {
              sendMessageCallback("", voiceFile, MessageType.VOICE)
            }
          } else {
            Log.e(TAG, "Failed to generate voice message for message $i")
          }
        }
        MessageType.VIDEO -> {
          val videoFile = BenchmarkMessageGenerator.getVideoFile(context, config.messageSize)
          if (videoFile != null) {
            withContext(Dispatchers.Main) {
              sendMessageCallback("Benchmark video #$i", videoFile, MessageType.VIDEO)
            }
          } else {
            Log.e(TAG, "Failed to generate video for message $i")
          }
        }
        MessageType.FILE, MessageType.LARGE_FILE -> {
          val file = BenchmarkMessageGenerator.generateTestFile(context, config.messageSize, config.messageType)
          withContext(Dispatchers.Main) {
            sendMessageCallback("Benchmark file #$i", file, config.messageType)
          }
        }
        MessageType.UNKNOWN -> {
          Log.w(TAG, "Unknown message type, skipping")
        }
      }
      
      // Wait before sending next message
      if (i < config.messageCount - 1) {
        delay(config.intervalMs)
      }
    }
    
    Log.i(TAG, "Sender session completed: ${config.messageCount} messages sent")
  }
  
  fun stopBenchmarkSession() {
    currentJob?.cancel()
    currentJob = null
    BenchmarkRecorder.disable()
    Log.i(TAG, "Benchmark session stopped")
  }
  
  fun isRunning(): Boolean {
    return currentJob?.isActive == true
  }
  
  fun exportCurrentReport(onExported: (File) -> Unit) {
    scope.launch {
      val report = BenchmarkRecorder.generateReport()
      val file = BenchmarkReporter.exportReport(context, report)
      
      withContext(Dispatchers.Main) {
        val summary = BenchmarkReporter.getReportSummary(report)
        Log.i(TAG, "Report exported:\n$summary")
        Toast.makeText(context, "Report exported to: ${file.name}", Toast.LENGTH_LONG).show()
        onExported(file)
      }
    }
  }
}

