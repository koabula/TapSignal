package org.thoughtcrime.securesms.conversation.v2

import android.view.Menu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.benchmark.BenchmarkConfig
import org.thoughtcrime.securesms.benchmark.BenchmarkController
import org.thoughtcrime.securesms.benchmark.BenchmarkRecorder
import org.thoughtcrime.securesms.benchmark.BenchmarkReporter
import org.thoughtcrime.securesms.benchmark.MessageType
import org.thoughtcrime.securesms.benchmark.ui.BenchmarkConfigDialog
import org.thoughtcrime.securesms.benchmark.ui.BenchmarkMenuExtension
import java.io.File

/**
 * Extension for ConversationFragment to add benchmark functionality.
 * This file is only included in the benchmark build variant.
 * 
 * Call this from ConversationFragment's ConversationOptionsMenuCallback.onOptionsMenuCreated
 */
object ConversationFragmentBenchmarkExtension {
  
  /**
   * Adds benchmark menu items to the conversation menu.
   * Only active in benchmark build variant.
   */
  fun addBenchmarkMenuItems(menu: Menu) {
    // Only add menu in benchmark build
    if (BuildConfig.BUILD_VARIANT_TYPE == "Benchmark") {
      BenchmarkMenuExtension.addBenchmarkMenuItems(menu)
    }
  }
  
  /**
   * Handles benchmark menu item selection
   */
  fun handleBenchmarkMenuSelection(
    fragment: Fragment,
    itemId: Int,
    onSendMessage: (text: String, file: File?, messageType: MessageType) -> Unit
  ): Boolean {
    if (BuildConfig.BUILD_VARIANT_TYPE != "Benchmark") {
      return false
    }
    
    return when (itemId) {
      BenchmarkMenuExtension.MENU_ITEM_ID_BENCHMARK_TEST -> {
        showBenchmarkDialog(fragment, onSendMessage)
        true
      }
      BenchmarkMenuExtension.MENU_ITEM_ID_BENCHMARK_EXPORT -> {
        exportBenchmarkReport(fragment)
        true
      }
      else -> false
    }
  }
  
  private fun showBenchmarkDialog(
    fragment: Fragment,
    onSendMessage: (text: String, file: File?, messageType: MessageType) -> Unit
  ) {
    val dialog = BenchmarkConfigDialog.newInstance { config ->
      startBenchmarkTest(fragment, config, onSendMessage)
    }
    dialog.show(fragment.childFragmentManager, "BenchmarkConfigDialog")
  }
  
  private fun startBenchmarkTest(
    fragment: Fragment,
    config: BenchmarkConfig,
    onSendMessage: (text: String, file: File?, messageType: MessageType) -> Unit
  ) {
    val context = fragment.requireContext()
    val controller = BenchmarkController(context, fragment.lifecycleScope)
    
    Toast.makeText(context, "Starting benchmark test...", Toast.LENGTH_SHORT).show()
    
    controller.startBenchmarkSession(
      config = config,
      onProgress = { current, total ->
        // Could show progress in UI if needed
      },
      onComplete = { report ->
        val summary = BenchmarkReporter.getReportSummary(report)
        Toast.makeText(
          context,
          "Benchmark complete: ${report.records.size} messages recorded",
          Toast.LENGTH_LONG
        ).show()
        
        // Auto-export report
        fragment.lifecycleScope.launch {
          val file = BenchmarkReporter.exportReport(context, report)
          BenchmarkReporter.shareReport(context, file)
        }
      },
      sendMessageCallback = onSendMessage
    )
  }
  
  private fun exportBenchmarkReport(fragment: Fragment) {
    val context = fragment.requireContext()
    
    if (!BenchmarkRecorder.isEnabled()) {
      Toast.makeText(
        context,
        "No active benchmark session. Start a test first.",
        Toast.LENGTH_SHORT
      ).show()
      return
    }
    
    fragment.lifecycleScope.launch {
      val report = BenchmarkRecorder.generateReport()
      
      if (report.records.isEmpty()) {
        Toast.makeText(
          context,
          "No benchmark data recorded yet",
          Toast.LENGTH_SHORT
        ).show()
        return@launch
      }
      
      val file = BenchmarkReporter.exportReport(context, report)
      val summary = BenchmarkReporter.getReportSummary(report)
      
      Toast.makeText(
        context,
        "Report exported: ${file.name}",
        Toast.LENGTH_LONG
      ).show()
      
      BenchmarkReporter.shareReport(context, file)
    }
  }
}

