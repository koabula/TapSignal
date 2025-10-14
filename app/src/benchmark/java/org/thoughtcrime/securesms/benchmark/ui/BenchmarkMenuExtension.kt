package org.thoughtcrime.securesms.benchmark.ui

import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import org.thoughtcrime.securesms.R

/**
 * Extension to add benchmark menu items to conversation screens.
 * This is only included in the benchmark build variant.
 */
object BenchmarkMenuExtension {
  
  const val MENU_ITEM_ID_BENCHMARK_TEST = 99999
  const val MENU_ITEM_ID_BENCHMARK_EXPORT = 99998
  
  fun addBenchmarkMenuItems(menu: Menu) {
    menu.add(
      Menu.NONE,
      MENU_ITEM_ID_BENCHMARK_TEST,
      Menu.NONE,
      "Start Benchmark Test"
    ).apply {
      setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }
    
    menu.add(
      Menu.NONE,
      MENU_ITEM_ID_BENCHMARK_EXPORT,
      Menu.NONE,
      "Export Benchmark Report"
    ).apply {
      setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
    }
  }
  
  fun handleBenchmarkMenuItemSelected(
    fragment: Fragment,
    itemId: Int,
    onStartTest: (BenchmarkConfigDialog) -> Unit,
    onExportReport: () -> Unit
  ): Boolean {
    return when (itemId) {
      MENU_ITEM_ID_BENCHMARK_TEST -> {
        showBenchmarkConfigDialog(fragment, onStartTest)
        true
      }
      MENU_ITEM_ID_BENCHMARK_EXPORT -> {
        onExportReport()
        true
      }
      else -> false
    }
  }
  
  private fun showBenchmarkConfigDialog(
    fragment: Fragment,
    onStartTest: (BenchmarkConfigDialog) -> Unit
  ) {
    val dialog = BenchmarkConfigDialog.newInstance { config ->
      // Dialog callback will be handled by the fragment
    }
    onStartTest(dialog)
    dialog.show(fragment.childFragmentManager, "BenchmarkConfigDialog")
  }
}

