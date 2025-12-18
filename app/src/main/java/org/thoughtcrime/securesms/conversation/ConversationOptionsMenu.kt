/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation

import android.content.Context
import android.text.SpannableString
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.annotation.IdRes
import androidx.core.view.MenuProvider
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.messagerequests.MessageRequestState
import org.thoughtcrime.securesms.recipients.Recipient

/**
 * Delegate object for managing the conversation options menu
 */
internal object ConversationOptionsMenu {

  private val TAG = Log.tag(ConversationOptionsMenu::class.java)

  /**
   * MenuProvider implementation for the conversation options menu.
   */
  class Provider(
    private val callback: Callback,
    private val lifecycleDisposable: LifecycleDisposable,
    var afterFirstRenderMode: Boolean = false
  ) : MenuProvider {

    private var createdPreRenderMenu = false

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
      if (createdPreRenderMenu && !afterFirstRenderMode) {
        return
      }

      menu.clear()

      val (
        recipient,
        isPushAvailable,
        canShowAsBubble,
        isActiveGroup,
        isActiveV2Group,
        isInActiveGroup,
        hasActiveGroupCall,
        distributionType,
        threadId,
        messageRequestState,
        isInBubble
      ) = callback.getSnapshot()

      if (recipient == null) {
        Log.w(TAG, "Recipient is null, no menu")
        return
      }

      if (!messageRequestState.isAccepted) {
        menuInflater.inflate(R.menu.conversation_message_request, menu)

        if (messageRequestState.isBlocked) {
          hideMenuItem(menu, R.id.menu_block)
          hideMenuItem(menu, R.id.menu_accept)
        } else {
          hideMenuItem(menu, R.id.menu_unblock)
        }

        if (messageRequestState.reportedAsSpam) {
          hideMenuItem(menu, R.id.menu_report_spam)
        }

        return
      }

      if (!afterFirstRenderMode) {
        createdPreRenderMenu = true
        if (recipient.isSelf) {
          return
        }

        menuInflater.inflate(R.menu.conversation_first_render, menu)

        if (recipient.isGroup) {
          hideMenuItem(menu, R.id.menu_call_secure)
          if (!isActiveV2Group) {
            hideMenuItem(menu, R.id.menu_video_secure)
          }
        } else if (!isPushAvailable) {
          hideMenuItem(menu, R.id.menu_call_secure)
          hideMenuItem(menu, R.id.menu_video_secure)
        }

        return
      }

      if (isPushAvailable) {
        if (recipient.expiresInSeconds > 0) {
          if (!isInActiveGroup) {
            menuInflater.inflate(R.menu.conversation_expiring_on, menu)
          }
          callback.showExpiring(recipient)
        } else {
          if (!isInActiveGroup) {
            menuInflater.inflate(R.menu.conversation_expiring_off, menu)
          }
          callback.clearExpiring()
        }
      }

      if (!recipient.isGroup) {
        if (isPushAvailable) {
          menuInflater.inflate(R.menu.conversation_callable_secure, menu)
        }
      } else if (recipient.isGroup) {
        if (isActiveV2Group) {
          menuInflater.inflate(R.menu.conversation_callable_groupv2, menu)
          if (hasActiveGroupCall) {
            hideMenuItem(menu, R.id.menu_video_secure)
          }
        }
        menuInflater.inflate(R.menu.conversation_group_options, menu)
        menuInflater.inflate(R.menu.conversation_active_group_options, menu)
      }

      menuInflater.inflate(R.menu.conversation, menu)

      // 动态设置COS v2模式菜单项
      updateCosV2MenuItem(menu, recipient)
      updateTapV3MenuItem(menu, recipient)

      if (!recipient.isGroup && !isPushAvailable && !recipient.isReleaseNotes) {
        menuInflater.inflate(R.menu.conversation_insecure, menu)
      }

      if (recipient.isMuted) menuInflater.inflate(R.menu.conversation_muted, menu) else menuInflater.inflate(R.menu.conversation_unmuted, menu)

      if (!recipient.isGroup && recipient.contactUri == null && !recipient.isReleaseNotes && !recipient.isSelf && recipient.hasE164 && recipient.shouldShowE164) {
        menuInflater.inflate(R.menu.conversation_add_to_contacts, menu)
      }

      if (recipient.isSelf) {
        if (isPushAvailable) {
          hideMenuItem(menu, R.id.menu_call_secure)
          hideMenuItem(menu, R.id.menu_video_secure)
        }
        hideMenuItem(menu, R.id.menu_mute_notifications)
      }

      if (recipient.isBlocked) {
        if (isPushAvailable) {
          hideMenuItem(menu, R.id.menu_call_secure)
          hideMenuItem(menu, R.id.menu_video_secure)
          hideMenuItem(menu, R.id.menu_expiring_messages)
          hideMenuItem(menu, R.id.menu_expiring_messages_off)
        }
        hideMenuItem(menu, R.id.menu_mute_notifications)
      }

      if (recipient.isReleaseNotes) {
        hideMenuItem(menu, R.id.menu_add_shortcut)
      }

      hideMenuItem(menu, R.id.menu_group_recipients)

      if (isActiveV2Group) {
        hideMenuItem(menu, R.id.menu_mute_notifications)
        hideMenuItem(menu, R.id.menu_conversation_settings)
      } else if (recipient.isGroup) {
        hideMenuItem(menu, R.id.menu_conversation_settings)
      }

      hideMenuItem(menu, R.id.menu_create_bubble)
      lifecycleDisposable += canShowAsBubble.subscribeBy(onNext = { yes: Boolean ->
        val item = menu.findItem(R.id.menu_create_bubble)
        if (item != null) {
          item.isVisible = yes && !isInBubble
        }
      })

      menu.findItem(R.id.menu_format_text_submenu).subMenu?.clearHeader()
      menu.findItem(R.id.edittext_bold).applyTitleSpan(MessageStyler.boldStyle())
      menu.findItem(R.id.edittext_italic).applyTitleSpan(MessageStyler.italicStyle())
      menu.findItem(R.id.edittext_strikethrough).applyTitleSpan(MessageStyler.strikethroughStyle())
      menu.findItem(R.id.edittext_monospace).applyTitleSpan(MessageStyler.monoStyle())

      callback.onOptionsMenuCreated(menu)
    }

    override fun onPrepareMenu(menu: Menu) {
      super.onPrepareMenu(menu)
      val formatText = menu.findItem(R.id.menu_format_text_submenu)
      if (formatText != null) {
        formatText.isVisible = callback.isTextHighlighted()
      }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
      when (menuItem.itemId) {
        R.id.menu_call_secure -> callback.handleDial()
        R.id.menu_video_secure -> callback.handleVideo()
        R.id.menu_view_media -> callback.handleViewMedia()
        R.id.menu_add_shortcut -> callback.handleAddShortcut()
        R.id.menu_search -> callback.handleSearch()
        R.id.menu_add_to_contacts -> callback.handleAddToContacts()
        R.id.menu_group_recipients -> callback.handleDisplayGroupRecipients()
        R.id.menu_group_settings -> callback.handleManageGroup()
        R.id.menu_leave -> callback.handleLeavePushGroup()
        R.id.menu_invite -> callback.handleInviteLink()
        R.id.menu_mute_notifications -> callback.handleMuteNotifications()
        R.id.menu_unmute_notifications -> callback.handleUnmuteNotifications()
        R.id.menu_conversation_settings -> callback.handleConversationSettings()
        R.id.menu_cos_v2_mode -> callback.handleCosV2ModeRequest()
        R.id.menu_tap_v3_mode -> callback.handleTapV3ModeRequest()
        R.id.menu_expiring_messages_off, R.id.menu_expiring_messages -> callback.handleSelectMessageExpiration()
        R.id.menu_create_bubble -> callback.handleCreateBubble()
        androidx.appcompat.R.id.home -> callback.handleGoHome()
        R.id.menu_block -> callback.handleBlock()
        R.id.menu_unblock -> callback.handleUnblock()
        R.id.menu_report_spam -> callback.handleReportSpam()
        R.id.menu_accept -> callback.handleMessageRequestAccept()
        R.id.menu_delete_chat -> callback.handleDeleteConversation()
        R.id.edittext_bold,
        R.id.edittext_italic,
        R.id.edittext_strikethrough,
        R.id.edittext_monospace,
        R.id.edittext_spoiler,
        R.id.edittext_clear_formatting -> callback.handleFormatText(menuItem.itemId)
        else -> return false
      }

      return true
    }

    private fun hideMenuItem(menu: Menu, @IdRes menuItem: Int) {
      if (menu.findItem(menuItem) != null) {
        menu.findItem(menuItem).isVisible = false
      }
    }

    /**
     * 动态更新COS v2模式菜单项（支持私聊和群组）
     */
    private fun updateCosV2MenuItem(menu: Menu, recipient: Recipient) {
      val cosMenuItem = menu.findItem(R.id.menu_cos_v2_mode)
      if (cosMenuItem != null) {
        // 排除不支持的recipient类型
        if (recipient.isSelf || recipient.isReleaseNotes) {
          cosMenuItem.isVisible = false
          return
        }

        // 如果用户被阻止，隐藏菜单项
        if (recipient.isBlocked) {
          cosMenuItem.isVisible = false
          return
        }

        try {
          val context = callback.getContext()
          
          // 处理群组的 v2 mode 状态
          if (recipient.isGroup) {
            try {
              val groupId = recipient.groupId.orElse(null)
              if (groupId == null) {
                cosMenuItem.isVisible = false
                return
              }
              
              val groupIdString = android.util.Base64.encodeToString(
                groupId.getDecodedId(),
                android.util.Base64.NO_WRAP
              )
              
              val groupManager = org.thoughtcrime.securesms.tap.group.GroupTransportManager.getInstance(context)
              val groupStatusResult = kotlinx.coroutines.runBlocking {
                groupManager.getGroupStatus(groupIdString)
              }
              
              if (groupStatusResult is org.thoughtcrime.securesms.tap.group.GroupOperationResult.Success) {
                val groupStatus = groupStatusResult.data
                cosMenuItem.isVisible = true
                when (groupStatus) {
                  org.thoughtcrime.securesms.tap.group.GroupV2Status.NATIVE -> {
                    cosMenuItem.setTitle(R.string.conversation__menu_use_group_v2_mode)
                  }
                  org.thoughtcrime.securesms.tap.group.GroupV2Status.PROPOSING -> {
                    val groupState = groupManager.getGroupStateSync(groupIdString)
                    val progress = if (groupState != null) {
                      " (${groupState.agreedMembers.size}/${groupState.totalMembers.size})"
                    } else {
                      ""
                    }
                    cosMenuItem.setTitle(context.getString(R.string.conversation__menu_group_v2_proposing) + progress)
                    cosMenuItem.isEnabled = false
                  }
                  org.thoughtcrime.securesms.tap.group.GroupV2Status.FULL_V2_ACTIVE -> {
                    cosMenuItem.setTitle(R.string.conversation__menu_disable_group_v2_mode)
                  }
                }
              } else {
                cosMenuItem.isVisible = false
              }
            } catch (e: Exception) {
              Log.e(TAG, "获取群组 v2 状态失败", e)
              cosMenuItem.isVisible = false
            }
            return
          }
          
          // 处理私聊的 v2 mode 状态
          val channelManager = org.thoughtcrime.securesms.tap.TransportChannelManager.getInstance(context)
          
          // 修复：先检查 ACI 是否存在，避免对新联系人（只有 PNI）调用 requireAci() 导致崩溃
          val recipientAci = recipient.serviceId.orElse(null)
          if (recipientAci == null || !recipientAci.isValid) {
            // 新搜索的联系人可能还没有 ACI，显示默认状态
            Log.d(TAG, "Recipient没有有效的ACI，显示默认v2 mode菜单")
            cosMenuItem.isVisible = true
            cosMenuItem.setTitle(R.string.conversation__menu_use_v2_mode)
            return
          }
          
          // 使用ACI字符串查询通道状态，与通道管理保持一致（只检查私聊通道）
          val hasActiveChannel = try {
              channelManager.hasActivePrivateChannel(recipientAci.toString())
          } catch (e: Throwable) {
              Log.w(TAG, "无法获取recipient ACI进行Tap通道查询: ${e.message}")
              false
          }

          cosMenuItem.isVisible = true
          if (hasActiveChannel) {
            cosMenuItem.setTitle(R.string.conversation__menu_disable_v2_mode)
          } else {
            cosMenuItem.setTitle(R.string.conversation__menu_use_v2_mode)
          }
        } catch (e: Throwable) {
          // 如果出错，使用默认标题但保持可见
          cosMenuItem.isVisible = true
          cosMenuItem.setTitle(R.string.conversation__menu_use_v2_mode)
          Log.e(TAG, "更新 v2 mode 菜单项失败", e)
        }
      }
    }
    
    private fun updateTapV3MenuItem(menu: Menu, recipient: Recipient) {
      val v3MenuItem = menu.findItem(R.id.menu_tap_v3_mode)
      if (v3MenuItem != null) {
        if (recipient.isSelf || recipient.isReleaseNotes) {
          v3MenuItem.isVisible = false
          return
        }
        
        if (recipient.isBlocked) {
          v3MenuItem.isVisible = false
          return
        }
        
        try {
          val context = callback.getContext()
          val tapV3Manager = org.thoughtcrime.securesms.tapv3.TapV3Manager.getInstance(context)
          
          if (!tapV3Manager.isConfigured()) {
            v3MenuItem.isVisible = false
            return
          }

          if (recipient.isGroup) {
            val groupId = recipient.groupId.orElse(null)
            if (groupId == null) {
              v3MenuItem.isVisible = false
              return
            }
            
            val groupIdString = android.util.Base64.encodeToString(
              groupId.getDecodedId(),
              android.util.Base64.NO_WRAP
            )
            
            val groupManager = org.thoughtcrime.securesms.tapv3.group.TapV3GroupManager.getInstance(context)
            val groupStatus = groupManager.getGroupStatus(groupIdString)
            
            v3MenuItem.isVisible = true
            if (groupStatus != null) {
              when (groupStatus.status) {
                org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupStatusTable.GroupStatus.ACTIVE -> {
                   v3MenuItem.setTitle(R.string.conversation__menu_disable_v3_mode)
                }
                org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupStatusTable.GroupStatus.PROPOSING -> {
                   val members = groupManager.getGroupMembers(groupIdString)
                   val total = members.size
                   val accepted = members.count { it.status == org.thoughtcrime.securesms.tapv3.group.database.TapV3GroupMembersTable.MemberStatus.ACCEPTED }
                   
                   val title = "Proposing v3 mode ($accepted/$total)"
                   v3MenuItem.title = title
                   v3MenuItem.isEnabled = true
                }
                else -> {
                   v3MenuItem.setTitle(R.string.conversation__menu_use_v3_mode)
                }
              }
            } else {
              v3MenuItem.setTitle(R.string.conversation__menu_use_v3_mode)
            }
            return
          }
          
          val recipientAci = try {
            recipient.requireAci().toString()
          } catch (e: Exception) {
            v3MenuItem.isVisible = false
            return
          }
          
          val channelTable = org.thoughtcrime.securesms.database.SignalDatabase.tapV3Channels
          val channel = channelTable.getChannel(recipientAci)
          
          v3MenuItem.isVisible = true
          if (channel != null && channel.status == org.thoughtcrime.securesms.tapv3.database.TapV3ChannelTable.ChannelStatus.ACTIVE) {
            v3MenuItem.setTitle(R.string.conversation__menu_disable_v3_mode)
          } else {
            v3MenuItem.setTitle(R.string.conversation__menu_use_v3_mode)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error updating v3 menu item", e)
          v3MenuItem.isVisible = false
        }
      }
    }

    private fun MenuItem.applyTitleSpan(span: Any) {
      title = SpannableString(title).apply { setSpan(span, 0, length, MessageStyler.SPAN_FLAGS) }
    }
  }

  /**
   * Data snapshot for building out menu state.
   */
  data class Snapshot(
    val recipient: Recipient?,
    val isPushAvailable: Boolean,
    val canShowAsBubble: Observable<Boolean>,
    val isActiveGroup: Boolean,
    val isActiveV2Group: Boolean,
    val isInActiveGroup: Boolean,
    val hasActiveGroupCall: Boolean,
    val distributionType: Int,
    val threadId: Long,
    val messageRequestState: MessageRequestState,
    val isInBubble: Boolean
  )

  /**
   * Callbacks abstraction for the converstaion options menu
   */
  interface Callback {
    fun getSnapshot(): Snapshot
    fun isTextHighlighted(): Boolean
    fun getContext(): Context

    fun onOptionsMenuCreated(menu: Menu)

    fun handleVideo()
    fun handleDial()
    fun handleViewMedia()
    fun handleAddShortcut()
    fun handleSearch()
    fun handleAddToContacts()
    fun handleDisplayGroupRecipients()
    fun handleManageGroup()
    fun handleLeavePushGroup()
    fun handleInviteLink()
    fun handleMuteNotifications()
    fun handleUnmuteNotifications()
    fun handleConversationSettings()
    fun handleCosV2ModeRequest()
    fun handleTapV3ModeRequest()
    fun handleSelectMessageExpiration()
    fun handleCreateBubble()
    fun handleGoHome()
    fun showExpiring(recipient: Recipient)
    fun clearExpiring()
    fun handleFormatText(@IdRes id: Int)
    fun handleBlock()
    fun handleUnblock()
    fun handleReportSpam()
    fun handleMessageRequestAccept()
    fun handleDeleteConversation()
  }
}
