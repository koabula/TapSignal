package org.thoughtcrime.securesms.benchmark

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Complete benchmark report containing all test data
 */
data class BenchmarkReport(
  val sessionId: String,
  val deviceRole: DeviceRole,
  val config: BenchmarkConfig?,
  val records: List<MessageBenchmarkRecord>,
  val generatedAt: Long,
  val deviceInfo: DeviceInfo = DeviceInfo.current(),
  val statistics: BenchmarkStatistics? = null
) {
  
  fun computeStatistics(): BenchmarkReport {
    val completedRecords = records.filter { it.isComplete() }
    
    if (completedRecords.isEmpty()) {
      return this.copy(statistics = null)
    }
    
    val stats = BenchmarkStatistics(
      totalMessages = records.size,
      completedMessages = completedRecords.size,
      failedMessages = records.size - completedRecords.size,
      
      avgEndToEndLatencyMs = completedRecords.mapNotNull { it.getEndToEndLatency() }.average(),
      medianEndToEndLatencyMs = completedRecords.mapNotNull { it.getEndToEndLatency() }.sorted().let { 
        if (it.isEmpty()) 0.0 else it[it.size / 2].toDouble() 
      },
      p95EndToEndLatencyMs = completedRecords.mapNotNull { it.getEndToEndLatency() }.sorted().let {
        if (it.isEmpty()) 0.0 else it[(it.size * 0.95).toInt()].toDouble()
      },
      
      avgEncryptionTimeMs = completedRecords.mapNotNull { it.getEncryptionTime() }.average(),
      avgUploadTimeMs = completedRecords.mapNotNull { it.getUploadTime() }.average(),
      avgPollingWaitTimeMs = completedRecords.mapNotNull { it.getPollingWaitTime() }.average(),
      avgDownloadTimeMs = completedRecords.mapNotNull { it.getDownloadTime() }.average(),
      avgDecryptionTimeMs = completedRecords.mapNotNull { it.getDecryptionTime() }.average(),
      avgUiRenderTimeMs = completedRecords.mapNotNull { it.getUiRenderTime() }.average()
    )
    
    return this.copy(statistics = stats)
  }
}

/**
 * Statistical summary of benchmark results
 */
data class BenchmarkStatistics(
  val totalMessages: Int,
  val completedMessages: Int,
  val failedMessages: Int,
  
  val avgEndToEndLatencyMs: Double,
  val medianEndToEndLatencyMs: Double,
  val p95EndToEndLatencyMs: Double,
  
  val avgEncryptionTimeMs: Double,
  val avgUploadTimeMs: Double,
  val avgPollingWaitTimeMs: Double,
  val avgDownloadTimeMs: Double,
  val avgDecryptionTimeMs: Double,
  val avgUiRenderTimeMs: Double
)

/**
 * Device information for context
 */
data class DeviceInfo(
  val manufacturer: String,
  val model: String,
  val androidVersion: String,
  val sdkInt: Int,
  val buildType: String
) {
  companion object {
    fun current(): DeviceInfo {
      return DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        buildType = Build.TYPE
      )
    }
  }
}

/**
 * Handles exporting benchmark reports to files and sharing
 */
object BenchmarkReporter {
  private const val TAG = "BenchmarkReporter"
  
  private val jsonMapper = ObjectMapper().apply {
    registerKotlinModule()
    enable(SerializationFeature.INDENT_OUTPUT)
  }
  
  fun exportReport(context: Context, report: BenchmarkReport): File {
    val reportWithStats = report.computeStatistics()
    val json = jsonMapper.writeValueAsString(reportWithStats)
    
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileName = "tap_benchmark_${report.deviceRole.name.lowercase()}_$timestamp.json"
    
    // Save to app's internal files directory
    val internalFile = File(context.filesDir, "benchmarks").apply { mkdirs() }
    val reportFile = File(internalFile, fileName)
    reportFile.writeText(json)
    
    Log.i(TAG, "Report saved to: ${reportFile.absolutePath}")
    
    // Try to also save to Downloads if possible
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // For Android 10+, save to app-specific external directory
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (externalDir != null) {
          val externalFile = File(externalDir, "benchmarks").apply { mkdirs() }
          val publicFile = File(externalFile, fileName)
          publicFile.writeText(json)
          Log.i(TAG, "Report also saved to external: ${publicFile.absolutePath}")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to save to external storage", e)
    }
    
    // Also save CSV format for easy analysis
    saveCsvReport(context, reportWithStats, timestamp)
    
    return reportFile
  }
  
  private fun saveCsvReport(context: Context, report: BenchmarkReport, timestamp: String) {
    try {
      val fileName = "tap_benchmark_${report.deviceRole.name.lowercase()}_$timestamp.csv"
      val csvFile = File(File(context.filesDir, "benchmarks"), fileName)
      
      val csv = buildString {
        appendLine("MessageID,MessageType,SizeBytes,T0_SendClicked,T1_EncryptStart,T2_EncryptEnd,T3_UploadStart,T4_UploadEnd,T5_PollDetected,T6_DownloadStart,T7_DownloadEnd,T8_DecryptStart,T9_DecryptEnd,T10_UiDisplayed,EndToEndLatency,EncryptionTime,UploadTime,PollingWaitTime,DownloadTime,DecryptionTime,UiRenderTime,Error")
        
        report.records.forEach { record ->
          appendLine("${record.messageId},${record.messageType},${record.messageSizeBytes},${record.t0_sendClicked ?: ""},${record.t1_encryptStart ?: ""},${record.t2_encryptEnd ?: ""},${record.t3_uploadStart ?: ""},${record.t4_uploadEnd ?: ""},${record.t5_pollDetected ?: ""},${record.t6_downloadStart ?: ""},${record.t7_downloadEnd ?: ""},${record.t8_decryptStart ?: ""},${record.t9_decryptEnd ?: ""},${record.t10_uiDisplayed ?: ""},${record.getEndToEndLatency() ?: ""},${record.getEncryptionTime() ?: ""},${record.getUploadTime() ?: ""},${record.getPollingWaitTime() ?: ""},${record.getDownloadTime() ?: ""},${record.getDecryptionTime() ?: ""},${record.getUiRenderTime() ?: ""},${record.errorMessage ?: ""}")
        }
      }
      
      csvFile.writeText(csv)
      Log.i(TAG, "CSV report saved to: ${csvFile.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save CSV report", e)
    }
  }
  
  fun shareReport(context: Context, reportFile: File) {
    try {
      val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        reportFile
      )
      
      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      
      context.startActivity(Intent.createChooser(shareIntent, "Export Benchmark Report"))
    } catch (e: Exception) {
      Log.e(TAG, "Failed to share report", e)
    }
  }
  
  fun getReportSummary(report: BenchmarkReport): String {
    val stats = report.statistics ?: return "No statistics available"
    
    return buildString {
      appendLine("=== Benchmark Report Summary ===")
      appendLine("Session ID: ${report.sessionId}")
      appendLine("Device Role: ${report.deviceRole}")
      appendLine("Device: ${report.deviceInfo.manufacturer} ${report.deviceInfo.model}")
      appendLine("Android: ${report.deviceInfo.androidVersion} (SDK ${report.deviceInfo.sdkInt})")
      appendLine()
      appendLine("Message Count: ${stats.completedMessages}/${stats.totalMessages}")
      appendLine("Failed: ${stats.failedMessages}")
      appendLine()
      appendLine("End-to-End Latency:")
      appendLine("  Average: %.2f ms".format(stats.avgEndToEndLatencyMs))
      appendLine("  Median:  %.2f ms".format(stats.medianEndToEndLatencyMs))
      appendLine("  P95:     %.2f ms".format(stats.p95EndToEndLatencyMs))
      appendLine()
      appendLine("Component Breakdown:")
      appendLine("  Encryption:     %.2f ms".format(stats.avgEncryptionTimeMs))
      appendLine("  Upload:         %.2f ms".format(stats.avgUploadTimeMs))
      appendLine("  Polling Wait:   %.2f ms".format(stats.avgPollingWaitTimeMs))
      appendLine("  Download:       %.2f ms".format(stats.avgDownloadTimeMs))
      appendLine("  Decryption:     %.2f ms".format(stats.avgDecryptionTimeMs))
      appendLine("  UI Render:      %.2f ms".format(stats.avgUiRenderTimeMs))
    }
  }
}

