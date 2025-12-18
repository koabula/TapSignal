package org.thoughtcrime.securesms.components.settings.conversation

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.Result
import org.signal.core.util.ThreadUtil
import org.signal.core.util.concurrent.SignalDispatchers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.readToList
import org.thoughtcrime.securesms.components.settings.conversation.preferences.ButtonStripPreference
import org.thoughtcrime.securesms.components.settings.conversation.preferences.CallPreference
import org.thoughtcrime.securesms.components.settings.conversation.preferences.LegacyGroupPreference
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.MediaTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.model.StoryViewState
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.LiveGroup
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason
import org.thoughtcrime.securesms.groups.v2.GroupAddMembersResult
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.messagerequests.MessageRequestRepository
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil
import org.thoughtcrime.securesms.util.livedata.Store
import org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable
import org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControlMessageSender
import org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo
import org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControl
import org.thoughtcrime.securesms.tap.group.GroupV2Status
import org.thoughtcrime.securesms.tap.group.GroupTransportManager
import org.thoughtcrime.securesms.tap.TransportChannelManager
import org.thoughtcrime.securesms.tap.integration.TapModuleInitializer
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.tap.TransportTokenPool
import org.thoughtcrime.securesms.tap.TransportTokenRequest
import org.thoughtcrime.securesms.tap.TransportProviderConfigManager
import org.thoughtcrime.securesms.tap.TapTokenExchangeMessage
import org.thoughtcrime.securesms.tap.TransportManager
import org.thoughtcrime.securesms.tap.TransportChannelConfig
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.tap.TransportPermission
import org.thoughtcrime.securesms.tap.notification.NotificationConfigManager
import org.thoughtcrime.securesms.tap.notification.NotificationConfig
import org.thoughtcrime.securesms.tap.utils.TapGatewayConfigBuilder
import org.thoughtcrime.securesms.tapv3.TapV3Result
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper

sealed class ConversationSettingsViewModel(
  private val callMessageIds: LongArray,
  private val repository: ConversationSettingsRepository,
  private val messageRequestRepository: MessageRequestRepository,
  specificSettingsState: SpecificSettingsState
) : ViewModel() {

  @Volatile
  private var cleared = false

  protected val store = Store(
    ConversationSettingsState(
      specificSettingsState = specificSettingsState,
      isDeprecatedOrUnregistered = SignalStore.misc.isClientDeprecated || TextSecurePreferences.isUnauthorizedReceived(AppDependencies.application)
    )
  )
  protected val internalEvents: Subject<ConversationSettingsEvent> = PublishSubject.create()

  private val sharedMediaUpdateTrigger = MutableLiveData(Unit)

  val state: LiveData<ConversationSettingsState> = store.stateLiveData
  val events: Observable<ConversationSettingsEvent> = internalEvents.observeOn(AndroidSchedulers.mainThread())

  protected val disposable = CompositeDisposable()

  init {
    val threadId: LiveData<Long> = state.map { it.threadId }.distinctUntilChanged()
    val updater: LiveData<Long> = LiveDataUtil.combineLatest(threadId, sharedMediaUpdateTrigger) { tId, _ -> tId }

    val sharedMedia: LiveData<List<MediaTable.MediaRecord>> = LiveDataUtil.mapAsync(SignalExecutors.BOUNDED, updater) { tId ->
      repository.getThreadMedia(threadId = tId, limit = 100)?.readToList { cursor ->
        MediaTable.MediaRecord.from(cursor)
      } ?: emptyList()
    }

    store.update(repository.getCallEvents(callMessageIds).toObservable()) { callRecords, state ->
      state.copy(calls = callRecords.map { (call, messageRecord) -> CallPreference.Model(call, messageRecord) })
    }

    store.update(sharedMedia) { mediaRecords, state ->
      if (!cleared) {
        state.copy(
          sharedMedia = mediaRecords,
          sharedMediaIds = mediaRecords.mapNotNull { it.attachment?.attachmentId?.id },
          sharedMediaLoaded = true,
          displayInternalRecipientDetails = repository.isInternalRecipientDetailsEnabled()
        )
      } else {
        state.copy(sharedMedia = emptyList())
      }
    }
  }

  fun refreshSharedMedia() {
    sharedMediaUpdateTrigger.postValue(Unit)
  }

  fun onReportSpam(): Maybe<Unit> {
    return if (store.state.threadId > 0 && store.state.recipient != Recipient.UNKNOWN) {
      messageRequestRepository.reportSpamMessageRequest(store.state.recipient.id, store.state.threadId)
        .observeOn(AndroidSchedulers.mainThread())
        .toSingle { Unit }
        .toMaybe()
    } else {
      Maybe.empty()
    }
  }

  fun onBlockAndReportSpam(): Maybe<Result<Unit, GroupChangeFailureReason>> {
    return if (store.state.threadId > 0 && store.state.recipient != Recipient.UNKNOWN) {
      messageRequestRepository.blockAndReportSpamMessageRequest(store.state.recipient.id, store.state.threadId)
        .observeOn(AndroidSchedulers.mainThread())
        .toMaybe()
    } else {
      Maybe.empty()
    }
  }

  open fun refreshRecipient(): Unit = error("This ViewModel does not support this interaction")

  abstract fun setMuteUntil(muteUntil: Long)

  abstract fun unmute()

  abstract fun block()

  abstract fun unblock()

  abstract fun onAddToGroup()

  abstract fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit)

  abstract fun revealAllMembers()

  abstract fun onToggleTapV3(enabled: Boolean)

  abstract fun onToggleTapV2(enabled: Boolean)

  override fun onCleared() {
    cleared = true
    store.clear()
    disposable.clear()
  }

  private class RecipientSettingsViewModel(
    private val recipientId: RecipientId,
    private val callMessageIds: LongArray,
    private val repository: ConversationSettingsRepository,
    messageRequestRepository: MessageRequestRepository
  ) : ConversationSettingsViewModel(
    callMessageIds,
    repository,
    messageRequestRepository,
    SpecificSettingsState.RecipientSettingsState()
  ) {

    private val liveRecipient = Recipient.live(recipientId)

    init {
      disposable += StoryViewState.getForRecipientId(recipientId).subscribe { storyViewState ->
        store.update { it.copy(storyViewState = storyViewState) }
      }

      val tapV2Status = LiveDataUtil.mapAsync(liveRecipient.liveData) { r ->
        val context = AppDependencies.application
        val channelManager = TransportChannelManager.getInstance(context)
        val recipientAci = r.aci.orElse(null)?.toString()
        if (recipientAci != null) {
          channelManager.hasActivePrivateChannel(recipientAci)
        } else {
          false
        }
      }

      store.update(tapV2Status) { enabled, state ->
        state.copy(
          specificSettingsState = state.requireRecipientSettingsState().copy(
            tapV2Enabled = enabled
          )
        )
      }

      val tapV3Status = LiveDataUtil.mapAsync(liveRecipient.liveData) { r ->
        val recipientAci = r.aci.orElse(null)?.toString()
        if (recipientAci != null) {
           val channel = SignalDatabase.tapV3Channels.getChannel(recipientAci)
           channel != null && channel.status != org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable.ChannelStatus.FAILED
        } else {
           false
        }
      }

      store.update(tapV3Status) { enabled, state ->
        state.copy(
          specificSettingsState = state.requireRecipientSettingsState().copy(
            tapV3Enabled = enabled
          )
        )
      }

      store.update(liveRecipient.liveData) { recipient, state ->
        val isAudioAvailable = recipient.isRegistered &&
          !recipient.isGroup &&
          !recipient.isBlocked &&
          !recipient.isSelf &&
          !recipient.isReleaseNotes

        state.copy(
          recipient = recipient,
          buttonStripState = ButtonStripPreference.State(
            isMessageAvailable = callMessageIds.isNotEmpty(),
            isVideoAvailable = recipient.registered == RecipientTable.RegisteredState.REGISTERED && !recipient.isSelf && !recipient.isBlocked && !recipient.isReleaseNotes,
            isAudioAvailable = isAudioAvailable,
            isAudioSecure = recipient.registered == RecipientTable.RegisteredState.REGISTERED,
            isMuted = recipient.isMuted,
            isMuteAvailable = !recipient.isSelf,
            isSearchAvailable = callMessageIds.isEmpty()
          ),
          disappearingMessagesLifespan = recipient.expiresInSeconds,
          canModifyBlockedState = !recipient.isSelf && RecipientUtil.isBlockable(recipient),
          specificSettingsState = state.requireRecipientSettingsState().copy(
            contactLinkState = when {
              recipient.isSelf || recipient.isReleaseNotes || recipient.isBlocked -> ContactLinkState.NONE
              recipient.isSystemContact -> ContactLinkState.OPEN
              recipient.hasE164 && recipient.shouldShowE164 -> ContactLinkState.ADD
              else -> ContactLinkState.NONE
            }
          )
        )
      }

      repository.getThreadId(recipientId) { threadId ->
        store.update { state ->
          state.copy(threadId = threadId)
        }
      }

      if (recipientId != Recipient.self().id) {
        disposable += repository.getGroupsInCommon(recipientId).subscribe { groupsInCommon ->
          store.update { state ->
            val recipientSettings = state.requireRecipientSettingsState()
            val canShowMore = !recipientSettings.groupsInCommonExpanded && groupsInCommon.size > 6

            state.copy(
              specificSettingsState = recipientSettings.copy(
                allGroupsInCommon = groupsInCommon,
                groupsInCommon = if (!canShowMore) groupsInCommon else groupsInCommon.take(5),
                canShowMoreGroupsInCommon = canShowMore
              )
            )
          }
        }

        repository.hasGroups { hasGroups ->
          store.update { state ->
            val recipientSettings = state.requireRecipientSettingsState()
            state.copy(
              specificSettingsState = recipientSettings.copy(
                selfHasGroups = hasGroups
              )
            )
          }
        }

        repository.getIdentity(recipientId) { identityRecord ->
          store.update { state ->
            state.copy(specificSettingsState = state.requireRecipientSettingsState().copy(identityRecord = identityRecord))
          }
        }
      }
    }

    override fun onAddToGroup() {
      repository.getGroupMembership(recipientId) {
        internalEvents.onNext(ConversationSettingsEvent.AddToAGroup(recipientId, it))
      }
    }

    override fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit) {
    }

    override fun revealAllMembers() {
      store.update { state ->
        state.copy(
          specificSettingsState = state.requireRecipientSettingsState().copy(
            groupsInCommon = state.requireRecipientSettingsState().allGroupsInCommon,
            groupsInCommonExpanded = true,
            canShowMoreGroupsInCommon = false
          )
        )
      }
    }

    override fun refreshRecipient() {
      repository.refreshRecipient(recipientId)
    }

    override fun setMuteUntil(muteUntil: Long) {
      repository.setMuteUntil(recipientId, muteUntil)
    }

    override fun unmute() {
      repository.setMuteUntil(recipientId, 0)
    }

    override fun block() {
      viewModelScope.launch {
        val result = withContext(SignalDispatchers.IO) {
          repository.block(recipientId)
        }

        if (!result.isSuccess) {
          internalEvents.onNext(ConversationSettingsEvent.ShowBlockGroupError(result.getFailureReason()))
        }
      }
    }

    override fun unblock() {
      repository.unblock(recipientId)
    }

    override fun onToggleTapV2(enabled: Boolean) {
      viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val recipient = store.state.recipient
        if (enabled) {
          // Check for active TapV3 channel and disable it if present
          try {
             val aci = recipient.aci.orElse(null)?.toString()
             if (aci != null) {
                val tapV3Channels = SignalDatabase.tapV3Channels
                val v3Channel = tapV3Channels.getChannel(aci)
                if (v3Channel != null && v3Channel.status == org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable.ChannelStatus.ACTIVE) {
                   val context = AppDependencies.application
                   val sender = org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessageSender.getInstance(context)
                   val closeMsg = org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessage.ChannelClose(reason = "Switching to V2")
                   sender.sendChannelClose(aci, closeMsg)

                   tapV3Channels.markChannelInactive(aci)
                   // Update state immediately
                   store.update { state ->
                     state.copy(
                       specificSettingsState = state.requireRecipientSettingsState().copy(
                         tapV3Enabled = false
                       )
                     )
                   }
                }
             }
          } catch (e: Exception) {
             org.signal.core.util.logging.Log.w("RecipientSettingsViewModel", "Failed to check/disable TapV3 when enabling TapV2", e)
          }
          sendTapV2ModeRequest(recipient)
        } else {
          disableTapV2Mode(recipient)
        }
      }
    }

    private suspend fun sendTapV2ModeRequest(recipient: Recipient) {
      try {
        val context = AppDependencies.application
        val channelManager = TransportChannelManager.getInstance(context)
        val configManager = TransportProviderConfigManager.getInstance(context)
        val providerType = "cos"
        val providerConfig = configManager.getProviderConfig(providerType) ?: return

        val tapInitializer = TapModuleInitializer.getInstance(context)
        tapInitializer.initializeSync()

        val transportManager = TransportManager.getInstance(context)
        if (!transportManager.isInitialized()) {
          transportManager.initialize()
        }

        val provider = transportManager.getProvider("cos") ?: return
        val recipientAci = recipient.requireAci().toString()
        val tokenPool = TransportTokenPool.getInstance(context)
        tokenPool.removeToken(recipientAci, "cos")

        val oldChannels = channelManager.getActiveChannels(recipientAci)
        oldChannels.forEach { oldChannel ->
          channelManager.closeChannel(oldChannel.channelId)
        }

        val tokenRequest = TransportTokenRequest(
          recipientId = recipientAci,
          providerType = "cos",
          requestedPermissions = setOf(
            TransportPermission.READ,
            TransportPermission.LIST
          ),
          validityDurationMs = 0L,
          providerConfig = providerConfig,
          purpose = "v2_mode_channel_for_peer"
        )

        val generatedToken = provider.generateToken(tokenRequest) ?: return
        val tokenSaved = tokenPool.addSharedToken(recipientAci, generatedToken)
        if (!tokenSaved) return

        val channelId = channelManager.createChannel(
          recipientId = recipient.id.toString(),
          config = TransportChannelConfig(),
          token = org.thoughtcrime.securesms.util.JsonUtils.toJson(generatedToken.toMap())
        )

        if (channelId != null) {
          val myAci = SignalStore.account.requireAci().toString()
          
          val notificationConfigManager = NotificationConfigManager.getInstance(context)
          val localNotificationConfig = notificationConfigManager.getLocalConfig()
          
          val tokenExchangeMessage = if (localNotificationConfig != null && localNotificationConfig.validate()) {
             val userId = localNotificationConfig.pushServiceInfo.metadata["userId"] as? String
                 ?: UUID.randomUUID().toString().replace("-", "").take(16)
             val gatewayConfig = TapGatewayConfigBuilder.build(localNotificationConfig)
             TapTokenExchangeMessage.createWithWebhook(
                senderAci = myAci,
                providerType = "cos",
                tokenData = generatedToken.toMap(),
                metadata = mapOf(
                  "channelId" to channelId,
                  "recipientAci" to recipientAci
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_OFFER,
                webhookUrl = localNotificationConfig.webhookUrl,
                notifySecret = localNotificationConfig.notifySecret,
                userId = userId,
                gatewayConfig = gatewayConfig
             )
          } else {
             TapTokenExchangeMessage(
                senderAci = myAci,
                providerType = "cos",
                tokenData = generatedToken.toMap(),
                metadata = mapOf(
                  "channelId" to channelId,
                  "recipientAci" to recipientAci
                ),
                requestType = TapTokenExchangeMessage.REQUEST_TYPE_OFFER
             )
          }

          val encodedMessage = TapTokenExchangeMessage.encode(tokenExchangeMessage)
          val outgoingMessage = OutgoingMessage.tapTokenExchangeMessage(
            threadRecipient = recipient,
            sentTimeMillis = System.currentTimeMillis(),
            expiresIn = recipient.expiresInSeconds * 1000L,
            tokenExchangeData = encodedMessage
          )

          val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
          MessageSender.send(
            context,
            outgoingMessage,
            threadId,
            MessageSender.SendType.SIGNAL,
            null,
            null
          )

          store.update { state ->
            state.copy(
              specificSettingsState = state.requireRecipientSettingsState().copy(
                tapV2Enabled = true
              )
            )
          }
        }
      } catch (e: Exception) {
        org.signal.core.util.logging.Log.e("RecipientSettingsViewModel", "Failed to enable Tap V2", e)
      }
    }

    private fun createTokenExchangeJson(token: org.thoughtcrime.securesms.tap.TransportToken, providerConfig: Map<String, Any>): String {
      return try {
        val tokenData = mapOf(
          "providerType" to token.providerType,
          "tokenId" to token.tokenId,
          "recipientId" to token.recipientId,
          "permissions" to token.permissions.map { it.name },
          "expirationTime" to token.expirationTime,
          "tokenData" to token.toMap(),
          "providerConfig" to mapOf(
            "region" to (providerConfig["region"] ?: ""),
            "bucketName" to (providerConfig["bucketName"] ?: ""),
            "provider" to (providerConfig["provider"] ?: "tencent")
          ),
          "timestamp" to System.currentTimeMillis(),
          "version" to "1.0"
        )

        val mapper = ObjectMapper()
        mapper.writeValueAsString(tokenData)
      } catch (e: Exception) {
        org.signal.core.util.logging.Log.e("RecipientSettingsViewModel", "Failed to create Token Exchange JSON", e)
        "{}"
      }
    }

    private suspend fun disableTapV2Mode(recipient: Recipient) {
       try {
         val context = AppDependencies.application
         val channelManager = TransportChannelManager.getInstance(context)
         val recipientAci = recipient.requireAci().toString()
         
         channelManager.revokeChannel(recipientAci)
         
         store.update { state ->
            state.copy(
              specificSettingsState = state.requireRecipientSettingsState().copy(
                tapV2Enabled = false
              )
            )
         }
       } catch (e: Exception) {
         org.signal.core.util.logging.Log.e("RecipientSettingsViewModel", "Failed to disable Tap V2", e)
       }
    }

    override fun onToggleTapV3(enabled: Boolean) {
      viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val recipient = store.state.recipient
        if (enabled) {
          try {
             val aci = recipient.aci.orElse(null)?.toString()
             if (aci != null) {
               // Check for active TapV2 channel and disable it if present
               try {
                  val context = AppDependencies.application
                  val channelManager = TransportChannelManager.getInstance(context)
                  if (channelManager.hasActivePrivateChannel(aci)) {
                      channelManager.revokeChannel(aci)
                      // Update state immediately
                      store.update { state ->
                        state.copy(
                          specificSettingsState = state.requireRecipientSettingsState().copy(
                            tapV2Enabled = false
                          )
                        )
                      }
                  }
               } catch (e: Exception) {
                  org.signal.core.util.logging.Log.w("RecipientSettingsViewModel", "Failed to check/disable TapV2 when enabling TapV3", e)
               }

               val context = AppDependencies.application
               val handshakeManager = org.thoughtcrime.securesms.tapv3.protocol.TapV3HandshakeManager.getInstance(context)
               val result = handshakeManager.initiateHandshake(aci)
               
               if (result is org.thoughtcrime.securesms.tapv3.TapV3Result.Success) {
                  store.update { state ->
                    state.copy(
                      specificSettingsState = state.requireRecipientSettingsState().copy(
                        tapV3Enabled = true
                      )
                    )
                  }
               } else {
                  org.signal.core.util.logging.Log.w("RecipientSettingsViewModel", "Failed to initiate TapV3 handshake: $result")
               }
             }
          } catch (e: Exception) {
            org.signal.core.util.logging.Log.e("RecipientSettingsViewModel", "Failed to enable Tap V3", e)
          }
        } else {
           try {
              val aci = recipient.aci.orElse(null)?.toString()
              if (aci != null) {
                  // Send Channel Close message to peer
                  val context = AppDependencies.application
                  val sender = org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessageSender.getInstance(context)
                  val closeMsg = org.thoughtcrime.securesms.tapv3.protocol.TapV3ControlMessage.ChannelClose(reason = "User disabled")
                  sender.sendChannelClose(aci, closeMsg)

                  val tapV3Channels = SignalDatabase.tapV3Channels
                  tapV3Channels.markChannelInactive(aci)
                  
                  store.update { state ->
                    state.copy(
                      specificSettingsState = state.requireRecipientSettingsState().copy(
                        tapV3Enabled = false
                      )
                    )
                  }
              }
           } catch (e: Exception) {
              org.signal.core.util.logging.Log.e("RecipientSettingsViewModel", "Failed to disable Tap V3", e)
           }
        }
      }
    }
  }

  private class GroupSettingsViewModel(
    private val groupId: GroupId,
    private val callMessageIds: LongArray,
    private val repository: ConversationSettingsRepository,
    messageRequestRepository: MessageRequestRepository
  ) : ConversationSettingsViewModel(callMessageIds, repository, messageRequestRepository, SpecificSettingsState.GroupSettingsState(groupId)) {

    private val liveGroup = LiveGroup(groupId)

    init {
      disposable += repository.getStoryViewState(groupId).subscribe { storyViewState ->
        store.update { it.copy(storyViewState = storyViewState) }
      }

      val recipientAndIsActive = LiveDataUtil.combineLatest(liveGroup.groupRecipient, liveGroup.isActive) { r, a -> r to a }
      store.update(recipientAndIsActive) { (recipient, isActive), state ->
        state.copy(
          recipient = recipient,
          buttonStripState = ButtonStripPreference.State(
            isMessageAvailable = callMessageIds.isNotEmpty(),
            isVideoAvailable = recipient.isPushV2Group && !recipient.isBlocked && isActive,
            isAudioAvailable = false,
            isAudioSecure = recipient.isPushV2Group,
            isMuted = recipient.isMuted,
            isMuteAvailable = true,
            isSearchAvailable = callMessageIds.isEmpty(),
            isAddToStoryAvailable = recipient.isPushV2Group && !recipient.isBlocked && isActive && !SignalStore.story.isFeatureDisabled
          ),
          canModifyBlockedState = RecipientUtil.isBlockable(recipient),
          specificSettingsState = state.requireGroupSettingsState().copy(
            legacyGroupState = getLegacyGroupState()
          )
        )
      }

      repository.getThreadId(groupId) { threadId ->
        store.update { state ->
          state.copy(threadId = threadId)
        }
      }

      store.update(liveGroup.selfCanEditGroupAttributes()) { selfCanEditGroupAttributes, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canEditGroupAttributes = selfCanEditGroupAttributes
          )
        )
      }

      store.update(liveGroup.isSelfAdmin) { isSelfAdmin, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            isSelfAdmin = isSelfAdmin
          )
        )
      }

      store.update(liveGroup.expireMessages) { expireMessages, state ->
        state.copy(
          disappearingMessagesLifespan = expireMessages
        )
      }

      store.update(liveGroup.selfCanAddMembers()) { canAddMembers, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canAddToGroup = canAddMembers
          )
        )
      }

      store.update(liveGroup.fullMembers) { fullMembers, state ->
        val groupState = state.requireGroupSettingsState()
        val canShowMore = !groupState.groupMembersExpanded && fullMembers.size > 6

        state.copy(
          specificSettingsState = groupState.copy(
            allMembers = fullMembers,
            members = if (!canShowMore) fullMembers else fullMembers.take(5),
            canShowMoreGroupMembers = canShowMore
          )
        )
      }

      store.update(liveGroup.isAnnouncementGroup) { announcementGroup, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            isAnnouncementGroup = announcementGroup
          )
        )
      }

      val isMessageRequestAccepted: LiveData<Boolean> = LiveDataUtil.mapAsync(liveGroup.groupRecipient) { r -> repository.isMessageRequestAccepted(r) }
      val descriptionState: LiveData<DescriptionState> = LiveDataUtil.combineLatest(liveGroup.description, isMessageRequestAccepted, ::DescriptionState)

      store.update(descriptionState) { d, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupDescription = d.description,
            groupDescriptionShouldLinkify = d.canLinkify,
            groupDescriptionLoaded = true
          )
        )
      }

      store.update(liveGroup.isActive) { isActive, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            canLeave = isActive && groupId.isPush
          )
        )
      }

      store.update(liveGroup.title) { title, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupTitle = title,
            groupTitleLoaded = true
          )
        )
      }

      store.update(liveGroup.groupLink) { groupLink, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            groupLinkEnabled = groupLink.isEnabled
          )
        )
      }

      store.update(repository.getMembershipCountDescription(liveGroup)) { description, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            membershipCountDescription = description
          )
        )
      }

      val tapV2Status = LiveDataUtil.mapAsync(liveGroup.groupRecipient) { r ->
        try {
          val table = SignalDatabase.groupV2Status
          val state = table.getGroupState(groupId.toString())
          state?.status ?: GroupV2Status.NATIVE
        } catch (e: Exception) {
          GroupV2Status.NATIVE
        }
      }

      store.update(tapV2Status) { status, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            tapV2Status = status
          )
        )
      }

      val tapV3Status = LiveDataUtil.mapAsync(liveGroup.groupRecipient) { r ->
        try {
          val context = AppDependencies.application
          val table = SignalDatabase.tapV3GroupStates
          val state = table.getGroupState(groupId.toString())
          state?.status == org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.ACTIVE
        } catch (e: Exception) {
          false
        }
      }

      store.update(tapV3Status) { enabled, state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            tapV3Enabled = enabled
          )
        )
      }
    }

    private fun getLegacyGroupState(): LegacyGroupPreference.State {
      return if (groupId.isMms) {
        LegacyGroupPreference.State.MMS_WARNING
      } else {
        LegacyGroupPreference.State.NONE
      }
    }

    override fun onAddToGroup() {
      repository.getGroupCapacity(groupId) { capacityResult ->
        if (capacityResult.getRemainingCapacity() > 0) {
          internalEvents.onNext(
            ConversationSettingsEvent.AddMembersToGroup(
              groupId,
              capacityResult.getSelectionWarning(),
              capacityResult.getSelectionLimit(),
              capacityResult.isAnnouncementGroup,
              capacityResult.getMembersWithoutSelf()
            )
          )
        } else {
          internalEvents.onNext(ConversationSettingsEvent.ShowGroupHardLimitDialog)
        }
      }
    }

    override fun onAddToGroupComplete(selected: List<RecipientId>, onComplete: () -> Unit) {
      repository.addMembers(groupId, selected) {
        ThreadUtil.runOnMain { onComplete() }

        when (it) {
          is GroupAddMembersResult.Success -> {
            if (it.newMembersInvited.isNotEmpty()) {
              internalEvents.onNext(ConversationSettingsEvent.ShowGroupInvitesSentDialog(it.newMembersInvited))
            }

            if (it.numberOfMembersAdded > 0) {
              internalEvents.onNext(ConversationSettingsEvent.ShowMembersAdded(it.numberOfMembersAdded))
            }
          }

          is GroupAddMembersResult.Failure -> internalEvents.onNext(ConversationSettingsEvent.ShowAddMembersToGroupError(it.reason))
        }
      }
    }

    override fun revealAllMembers() {
      store.update { state ->
        state.copy(
          specificSettingsState = state.requireGroupSettingsState().copy(
            members = state.requireGroupSettingsState().allMembers,
            groupMembersExpanded = true,
            canShowMoreGroupMembers = false
          )
        )
      }
    }

    override fun setMuteUntil(muteUntil: Long) {
      repository.setMuteUntil(groupId, muteUntil)
    }

    override fun unmute() {
      repository.setMuteUntil(groupId, 0)
    }

    override fun block() {
      viewModelScope.launch {
        val result = withContext(SignalDispatchers.IO) {
          repository.block(groupId)
        }

        if (!result.isSuccess) {
          internalEvents.onNext(ConversationSettingsEvent.ShowBlockGroupError(result.getFailureReason()))
        }
      }
    }

    override fun unblock() {
      repository.unblock(groupId)
    }

    override fun onToggleTapV2(enabled: Boolean) {
       viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
         try {
             val manager = GroupTransportManager.getInstance(AppDependencies.application)
             val groupIdString = groupId.toString()
             if (enabled) {
                // Check and disable TapV3 if active
                try {
                    val tapV3Table = SignalDatabase.tapV3GroupStates
                    val v3State = tapV3Table.getGroupState(groupIdString)
                    if (v3State != null && v3State.status != org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.NATIVE) {
                        val sender = org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControlMessageSender(AppDependencies.application)
                        val disable = org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControl.Disable(reason = "Switching to V2")
                        sender.sendDisable(groupIdString, disable)
                        tapV3Table.updateStatus(groupIdString, org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.NATIVE)
                        store.update { state ->
                            state.copy(
                                specificSettingsState = state.requireGroupSettingsState().copy(
                                    tapV3Enabled = false
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    org.signal.core.util.logging.Log.w("GroupSettingsViewModel", "Failed to disable TapV3 when enabling TapV2", e)
                }

                // Fetch members if needed, or rely on internal logic.
                // However, proposeV2ModeComplete requires memberRecipientIds.
                val members = store.state.specificSettingsState.requireGroupSettingsState().allMembers.map { it.member.id }
                // If members are empty, we might fail.
                if (members.isEmpty()) {
                    org.signal.core.util.logging.Log.w("GroupSettingsViewModel", "No members found for group $groupIdString")
                    return@launch
                }
                val result = manager.proposeV2ModeComplete(groupIdString, members, "cos")
                if (result) {
                    // Update UI optimistically or wait for DB update
                    // Since manager updates DB, LiveData should handle it.
                    // But we can force a refresh if needed.
                }
             } else {
                manager.disableV2ModeComplete(groupIdString)
             }
         } catch (e: Exception) {
             org.signal.core.util.logging.Log.e("GroupSettingsViewModel", "Failed to toggle Group Tap V2", e)
         }
       }
    }

    override fun onToggleTapV3(enabled: Boolean) {
      viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
          val context = AppDependencies.application
          val sender = org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControlMessageSender(context)
          val table = SignalDatabase.tapV3GroupStates
          
          if (enabled) {
            // Check and disable TapV2 if active
            try {
                val manager = GroupTransportManager.getInstance(context)
                val v2StatusResult = manager.getGroupStatus(groupId.toString())
                if (v2StatusResult is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Success && 
                    v2StatusResult.data != org.thoughtcrime.securesms.tap.group.GroupV2Status.NATIVE) {
                    manager.disableV2ModeComplete(groupId.toString())
                    // UI update for V2 should happen via LiveData observing V2 status
                }
            } catch (e: Exception) {
                org.signal.core.util.logging.Log.w("GroupSettingsViewModel", "Failed to disable TapV2 when enabling TapV3", e)
            }

            val handshakeInfo = org.thoughtcrime.securesms.tapv3.TapV3HandshakeInfo.create(context)
            val offer = org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControl.Offer(handshakeInfo = handshakeInfo)
            sender.sendOffer(groupId.toString(), offer)

            val current = table.getGroupState(groupId.toString())
            val selfAci = Recipient.self().aci.orElse(null)?.toString() ?: return@launch

            if (current == null) {
              val record = org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStateRecord(
                groupId = groupId.toString(),
                status = org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.PROPOSING,
                initiatorId = selfAci,
                agreedMembers = listOf(selfAci),
                handshakeInfo = handshakeInfo,
                updatedAt = System.currentTimeMillis()
              )
              table.setGroupState(record)
            } else {
              table.updateStatus(groupId.toString(), org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.PROPOSING)
            }
          } else {
            val disable = org.thoughtcrime.securesms.tapv3.protocol.TapV3GroupControl.Disable(reason = "User disabled")
            sender.sendDisable(groupId.toString(), disable)
            table.updateStatus(groupId.toString(), org.thoughtcrime.securesms.tapv3.database.TapV3GroupStateTable.GroupStatus.NATIVE)
            table.updateAgreedMembers(groupId.toString(), emptyList())
          }

          store.update { state ->
            state.copy(
              specificSettingsState = state.requireGroupSettingsState().copy(
                tapV3Enabled = enabled
              )
            )
          }
        } catch (e: Exception) {
          org.signal.core.util.logging.Log.e("GroupSettingsViewModel", "Failed to toggle Tap v3", e)
        }
      }
    }
  }

  class Factory(
    private val recipientId: RecipientId? = null,
    private val groupId: GroupId? = null,
    private val callMessageIds: LongArray,
    private val repository: ConversationSettingsRepository,
    private val messageRequestRepository: MessageRequestRepository
  ) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(
        modelClass.cast(
          when {
            recipientId != null -> RecipientSettingsViewModel(recipientId, callMessageIds, repository, messageRequestRepository)
            groupId != null -> GroupSettingsViewModel(groupId, callMessageIds, repository, messageRequestRepository)
            else -> error("One of RecipientId or GroupId required.")
          }
        )
      )
    }
  }

  private class DescriptionState(
    val description: String?,
    val canLinkify: Boolean
  )
}
