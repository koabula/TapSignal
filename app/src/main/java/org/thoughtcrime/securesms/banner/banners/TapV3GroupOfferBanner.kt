package org.thoughtcrime.securesms.banner.banners

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.banner.Banner
import org.thoughtcrime.securesms.banner.ui.compose.Action
import org.thoughtcrime.securesms.banner.ui.compose.DefaultBanner
import org.thoughtcrime.securesms.recipients.Recipient

class TapV3GroupOfferBanner(
  private val initiator: Recipient,
  private val onAgree: () -> Unit
) : Banner<Recipient>() {

  override val enabled: Boolean = true

  override val dataFlow: Flow<Recipient> = flowOf(initiator)

  @Composable
  override fun DisplayBanner(model: Recipient, contentPadding: PaddingValues) {
    Banner(
      contentPadding = contentPadding,
      initiator = model,
      onAgree = onAgree
    )
  }
}

@Composable
private fun Banner(
  contentPadding: PaddingValues,
  initiator: Recipient,
  onAgree: () -> Unit
) {
  var visible by remember { mutableStateOf(true) }
  val context = LocalContext.current

  if (!visible) {
    return
  }

  DefaultBanner(
    title = null,
    body = stringResource(R.string.TapV3GroupOfferBanner__received_offer, initiator.getDisplayName(context)),
    onDismissListener = { visible = false },
    actions = listOf(
      Action(R.string.TapV3GroupOfferBanner__agree, onClick = onAgree)
    ),
    paddingValues = contentPadding
  )
}
