package org.thoughtcrime.securesms.benchmark.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.benchmark.BenchmarkConfig
import org.thoughtcrime.securesms.benchmark.DeviceRole
import org.thoughtcrime.securesms.benchmark.MessageSize
import org.thoughtcrime.securesms.benchmark.MessageType
import org.thoughtcrime.securesms.databinding.DialogBenchmarkConfigBinding

/**
 * Dialog for configuring a benchmark test session
 */
class BenchmarkConfigDialog : DialogFragment() {
  
  private var _binding: DialogBenchmarkConfigBinding? = null
  private val binding get() = _binding!!
  
  private var onConfigConfirmed: ((BenchmarkConfig) -> Unit)? = null
  
  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    _binding = DialogBenchmarkConfigBinding.inflate(layoutInflater)
    
    setupViews()
    
    return AlertDialog.Builder(requireContext())
      .setTitle("Configure Benchmark Test")
      .setView(binding.root)
      .setPositiveButton("Start Test") { _, _ ->
        val config = buildConfig()
        onConfigConfirmed?.invoke(config)
      }
      .setNegativeButton("Cancel", null)
      .create()
  }
  
  private fun setupViews() {
    // Message type spinner
    val messageTypes = MessageType.values().filter { it != MessageType.UNKNOWN }
    binding.spinnerMessageType.adapter = ArrayAdapter(
      requireContext(),
      android.R.layout.simple_spinner_item,
      messageTypes.map { it.name }
    ).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    
    // Message size spinner
    val messageSizes = MessageSize.values()
    binding.spinnerMessageSize.adapter = ArrayAdapter(
      requireContext(),
      android.R.layout.simple_spinner_item,
      messageSizes.map { it.name }
    ).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    
    // Device role spinner
    val deviceRoles = listOf(DeviceRole.SENDER, DeviceRole.RECEIVER, DeviceRole.BOTH)
    binding.spinnerDeviceRole.adapter = ArrayAdapter(
      requireContext(),
      android.R.layout.simple_spinner_item,
      deviceRoles.map { it.name }
    ).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    
    // Set defaults
    binding.editMessageCount.setText("50")
    binding.editIntervalMs.setText("1000")
    binding.checkboxAutoStartV2.isChecked = true
  }
  
  private fun buildConfig(): BenchmarkConfig {
    val messageCount = binding.editMessageCount.text.toString().toIntOrNull() ?: 50
    val messageType = MessageType.valueOf(binding.spinnerMessageType.selectedItem.toString())
    val messageSize = MessageSize.valueOf(binding.spinnerMessageSize.selectedItem.toString())
    val intervalMs = binding.editIntervalMs.text.toString().toLongOrNull() ?: 1000L
    val deviceRole = DeviceRole.valueOf(binding.spinnerDeviceRole.selectedItem.toString())
    val autoStartV2 = binding.checkboxAutoStartV2.isChecked
    
    return BenchmarkConfig(
      messageCount = messageCount,
      messageType = messageType,
      messageSize = messageSize,
      intervalMs = intervalMs,
      deviceRole = deviceRole,
      autoStartV2Mode = autoStartV2
    )
  }
  
  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
  
  companion object {
    fun newInstance(onConfigConfirmed: (BenchmarkConfig) -> Unit): BenchmarkConfigDialog {
      return BenchmarkConfigDialog().apply {
        this.onConfigConfirmed = onConfigConfirmed
      }
    }
  }
}

