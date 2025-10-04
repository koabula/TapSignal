# 群组 V2 Mode UI 集成指南

## 概述

本文档说明如何在 Signal Android 的群组界面中集成 V2 mode 相关的 UI 元素。

## 1. 群组菜单集成

### 1.1 添加"Use v2 mode"菜单项

在群组对话界面的菜单中添加选项。

**涉及文件**（示例）：
- `app/src/main/java/org/thoughtcrime/securesms/conversation/v2/ConversationFragment.kt`
- 或群组对话相关的 Fragment/Activity

**实现步骤**：

```kotlin
// 在创建群组菜单的方法中添加

fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
    // ... 现有菜单项 ...
    
    // 检查是否为群组
    if (recipient.isGroup) {
        val groupId = getGroupId(recipient)
        val groupManager = GroupTransportManager.getInstance(requireContext())
        val groupStatus = groupManager.getGroupStatus(groupId)
        
        // 根据当前状态显示不同的菜单项
        when (groupStatus) {
            GroupV2Status.NATIVE -> {
                // 显示"Use v2 mode"选项
                menu.add(0, R.id.menu_use_v2_mode, 0, "Use v2 mode")
                    .setIcon(R.drawable.ic_v2_mode)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            GroupV2Status.PROPOSING -> {
                // 显示提议进度
                val groupState = groupManager.getGroupState(groupId)
                if (groupState != null) {
                    val progress = "${groupState.agreedMembers.size}/${groupState.totalMembers.size} 已同意"
                    menu.add(0, R.id.menu_v2_mode_progress, 0, "v2 mode: $progress")
                        .setEnabled(false)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
            }
            GroupV2Status.FULL_V2_ACTIVE -> {
                // 显示"Disable v2 mode"选项
                menu.add(0, R.id.menu_disable_v2_mode, 0, "Disable v2 mode")
                    .setIcon(R.drawable.ic_v2_mode_active)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
    }
}

fun onMenuItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
        R.id.menu_use_v2_mode -> {
            handleUseV2Mode()
            return true
        }
        R.id.menu_disable_v2_mode -> {
            handleDisableV2Mode()
            return true
        }
        else -> return false
    }
}

private fun handleUseV2Mode() {
    // 显示确认对话框
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("启用 v2 mode")
        .setMessage("是否将此群组升级到 v2 mode？这将需要所有成员同意。")
        .setPositiveButton("确定") { _, _ ->
            proposeGroupV2Mode()
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun proposeGroupV2Mode() {
    lifecycleScope.launch {
        try {
            val recipient = viewModel.recipient.value ?: return@launch
            val groupId = getGroupId(recipient)
            val memberRecipientIds = recipient.participantIds
            
            // 使用默认 provider（如 "cos"）
            val providerType = "cos"
            
            val groupManager = GroupTransportManager.getInstance(requireContext())
            val success = groupManager.proposeV2ModeComplete(
                groupId = groupId,
                memberRecipientIds = memberRecipientIds,
                providerType = providerType
            )
            
            if (success) {
                Toast.makeText(requireContext(), "v2 mode 提议已发送", Toast.LENGTH_SHORT).show()
                // 刷新菜单
                requireActivity().invalidateOptionsMenu()
            } else {
                Toast.makeText(requireContext(), "发送提议失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "发起 v2 mode 提议失败", e)
            Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun handleDisableV2Mode() {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("禁用 v2 mode")
        .setMessage("是否禁用此群组的 v2 mode？将回退到使用 Signal Server。")
        .setPositiveButton("确定") { _, _ ->
            disableGroupV2Mode()
        }
        .setNegativeButton("取消", null)
        .show()
}

private fun disableGroupV2Mode() {
    lifecycleScope.launch {
        try {
            val recipient = viewModel.recipient.value ?: return@launch
            val groupId = getGroupId(recipient)
            val memberRecipientIds = recipient.participantIds
            
            val groupManager = GroupTransportManager.getInstance(requireContext())
            val groupState = groupManager.getGroupState(groupId)
            
            if (groupState != null) {
                val myAci = SignalStore.account.requireAci().toString()
                val helper = GroupTokenExchangeHelper.getInstance(requireContext())
                
                // 发送禁用消息
                val sent = helper.sendGroupDisableMessage(
                    groupId = groupId,
                    senderAci = myAci,
                    memberRecipientIds = memberRecipientIds,
                    providerType = groupState.providerType
                )
                
                if (sent) {
                    // 本地禁用
                    groupManager.disableV2Mode(groupId)
                    
                    Toast.makeText(requireContext(), "v2 mode 已禁用", Toast.LENGTH_SHORT).show()
                    requireActivity().invalidateOptionsMenu()
                } else {
                    Toast.makeText(requireContext(), "禁用失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "禁用 v2 mode 失败", e)
            Toast.makeText(requireContext(), "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 从 Recipient 获取 GroupId 字符串
 */
private fun getGroupId(recipient: Recipient): String {
    // 方法1：如果 groupId 是 GroupId 对象
    val groupId = recipient.groupId.orNull()
    if (groupId != null) {
        // 转换为 Base64 字符串或其他格式
        return android.util.Base64.encodeToString(
            groupId.decodedId,
            android.util.Base64.NO_WRAP
        )
    }
    
    // 方法2：如果使用 RecipientId
    return recipient.id.serialize()
}
```

### 1.2 添加 menu 资源

**文件**: `app/src/main/res/menu/conversation_group_menu.xml` (或相应的菜单文件)

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
      xmlns:app="http://schemas.android.com/apk/res-auto">
    
    <!-- 现有菜单项 -->
    
    <!-- v2 mode 相关菜单项 -->
    <item
        android:id="@+id/menu_use_v2_mode"
        android:title="@string/menu_use_v2_mode"
        android:icon="@drawable/ic_v2_mode"
        app:showAsAction="never" />
    
    <item
        android:id="@+id/menu_disable_v2_mode"
        android:title="@string/menu_disable_v2_mode"
        android:icon="@drawable/ic_v2_mode_active"
        app:showAsAction="never" />
        
    <item
        android:id="@+id/menu_v2_mode_progress"
        android:title="@string/menu_v2_mode_progress"
        android:enabled="false"
        app:showAsAction="never" />
</menu>
```

### 1.3 添加字符串资源

**文件**: `app/src/main/res/values/strings.xml`

```xml
<!-- v2 mode 相关字符串 -->
<string name="menu_use_v2_mode">Use v2 mode</string>
<string name="menu_disable_v2_mode">Disable v2 mode</string>
<string name="menu_v2_mode_progress">v2 mode: %1$d/%2$d agreed</string>

<string name="dialog_use_v2_mode_title">启用 v2 mode</string>
<string name="dialog_use_v2_mode_message">是否将此群组升级到 v2 mode？这将需要所有成员同意。</string>
<string name="dialog_disable_v2_mode_title">禁用 v2 mode</string>
<string name="dialog_disable_v2_mode_message">是否禁用此群组的 v2 mode？将回退到使用 Signal Server。</string>

<string name="toast_v2_mode_proposal_sent">v2 mode 提议已发送</string>
<string name="toast_v2_mode_proposal_failed">发送提议失败</string>
<string name="toast_v2_mode_disabled">v2 mode 已禁用</string>
<string name="toast_v2_mode_disable_failed">禁用失败</string>
```

## 2. 状态指示器

### 2.1 在对话顶部显示 v2 mode 状态

**实现位置**: 对话标题栏或信息栏

```kotlin
// 在 ConversationFragment 或对应的 ViewModel 中

fun updateV2ModeIndicator() {
    val recipient = viewModel.recipient.value ?: return
    
    if (recipient.isGroup) {
        val groupId = getGroupId(recipient)
        val groupManager = GroupTransportManager.getInstance(requireContext())
        val groupStatus = groupManager.getGroupStatus(groupId)
        
        when (groupStatus) {
            GroupV2Status.NATIVE -> {
                // 不显示指示器
                v2ModeIndicator.visibility = View.GONE
            }
            GroupV2Status.PROPOSING -> {
                // 显示"提议中"指示器
                val groupState = groupManager.getGroupState(groupId)
                if (groupState != null) {
                    v2ModeIndicator.visibility = View.VISIBLE
                    v2ModeIndicator.text = "v2 mode 提议中 (${groupState.agreedMembers.size}/${groupState.totalMembers.size})"
                    v2ModeIndicator.setBackgroundColor(Color.parseColor("#FFA500")) // 橙色
                }
            }
            GroupV2Status.FULL_V2_ACTIVE -> {
                // 显示"已启用"指示器
                v2ModeIndicator.visibility = View.VISIBLE
                v2ModeIndicator.text = "v2 mode 已启用"
                v2ModeIndicator.setBackgroundColor(Color.parseColor("#00AA00")) // 绿色
            }
        }
    }
}
```

### 2.2 布局示例

**文件**: `app/src/main/res/layout/conversation_fragment.xml` (或相应布局)

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">
    
    <!-- v2 mode 状态指示器 -->
    <TextView
        android:id="@+id/v2_mode_indicator"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="8dp"
        android:gravity="center"
        android:textColor="@android:color/white"
        android:visibility="gone"
        tools:visibility="visible"
        tools:text="v2 mode 已启用" />
    
    <!-- 对话消息列表 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/message_list"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
        
    <!-- ... 其他 UI 元素 ... -->
</LinearLayout>
```

## 3. 注册 BroadcastReceiver

确保 `GroupTokenExchangeReceiver` 在 AndroidManifest.xml 中注册。

**文件**: `app/src/main/AndroidManifest.xml`

```xml
<manifest>
    <application>
        <!-- 现有组件 -->
        
        <!-- 群组 Token 交换接收器 -->
        <receiver
            android:name="org.thoughtcrime.securesms.tap.group.GroupTokenExchangeReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="ACCEPT_GROUP_TOKEN_EXCHANGE" />
                <action android:name="REJECT_GROUP_TOKEN_EXCHANGE" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

## 4. 监听状态变化

为了在状态变化时自动更新 UI，可以使用 LiveData 或 Flow。

### 4.1 在 ViewModel 中监听

```kotlin
class ConversationViewModel : ViewModel() {
    
    private val _groupV2Status = MutableLiveData<GroupV2Status>()
    val groupV2Status: LiveData<GroupV2Status> = _groupV2Status
    
    fun observeGroupV2Status(groupId: String) {
        viewModelScope.launch {
            // 定期检查或使用数据库观察
            while (isActive) {
                val groupManager = GroupTransportManager.getInstance(getApplication())
                val status = groupManager.getGroupStatus(groupId)
                _groupV2Status.postValue(status)
                delay(1000) // 每秒检查一次
            }
        }
    }
}
```

### 4.2 在 Fragment 中观察

```kotlin
viewModel.groupV2Status.observe(viewLifecycleOwner) { status ->
    updateV2ModeIndicator()
    requireActivity().invalidateOptionsMenu() // 刷新菜单
}
```

## 5. 完整示例代码结构

```
app/src/main/
├── java/org/thoughtcrime/securesms/
│   ├── conversation/v2/
│   │   ├── ConversationFragment.kt      (修改)
│   │   └── ConversationViewModel.kt     (修改)
│   └── tap/group/
│       ├── GroupTransportManager.kt     (已实现)
│       ├── GroupTokenExchangeHelper.kt  (已实现)
│       └── GroupTokenExchangeReceiver.kt (已实现)
└── res/
    ├── layout/
    │   └── conversation_fragment.xml    (修改)
    ├── menu/
    │   └── conversation_group_menu.xml  (修改)
    ├── values/
    │   └── strings.xml                  (添加)
    └── drawable/
        ├── ic_v2_mode.xml              (新增)
        └── ic_v2_mode_active.xml       (新增)
```

## 6. 测试清单

### 功能测试
- [ ] 群组菜单显示"Use v2 mode"选项
- [ ] 点击"Use v2 mode"后显示确认对话框
- [ ] 确认后发送提议消息
- [ ] 收到提议后显示通知
- [ ] 点击通知的"同意"按钮
- [ ] 全员同意后自动激活
- [ ] 激活后显示状态指示器
- [ ] "Disable v2 mode"功能正常

### UI 测试
- [ ] 状态指示器显示正确
- [ ] 提议进度显示正确
- [ ] 菜单项根据状态动态显示/隐藏
- [ ] Toast 提示显示正常

### 边界情况
- [ ] 网络异常时的处理
- [ ] 快速重复点击的处理
- [ ] 提议被拒绝后的处理

## 7. 注意事项

1. **GroupId 格式**: Signal 中 GroupId 可能有多种表示方式（字节数组、Base64 字符串等），需要根据实际情况转换。

2. **权限检查**: 确保只有群组管理员或所有成员都可以发起提议（根据产品需求）。

3. **错误处理**: 添加完善的错误处理和用户提示。

4. **性能**: 避免频繁查询数据库，使用缓存或观察者模式。

5. **国际化**: 所有用户可见的字符串都应该支持多语言。

---

**版本**: 1.0  
**最后更新**: 2025-10-04  
**状态**: 待实现

