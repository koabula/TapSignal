package org.thoughtcrime.securesms.tapv3.integration

import android.content.Context
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.tapv3.utils.TapV3Logger

class GroupMemberResolver(private val context: Context) {

    private val groupTable = SignalDatabase.groups

    /**
     * Resolve group members ACIs given a group ID.
     * Returns a list of ACIs (strings) for members excluding self.
     */
    fun getGroupMemberAcis(groupIdString: String): List<String> {
        try {
            val groupId = GroupId.parseOrThrow(groupIdString)
            // We want all members except self
            val memberSet = GroupTable.MemberSet.FULL_MEMBERS_EXCLUDING_SELF
            
            val memberIds = groupTable.getGroupMemberIds(groupId, memberSet)
            
            return memberIds.mapNotNull { id ->
                val recipient = Recipient.resolved(id)
                recipient.aci.orElse(null)?.toString()
            }
        } catch (e: Exception) {
            TapV3Logger.e(TAG, "Failed to resolve group members for groupId: $groupIdString", e)
            return emptyList()
        }
    }

    companion object {
        private const val TAG = "TapV3GroupMemberResolver"
        
        @Volatile
        private var INSTANCE: GroupMemberResolver? = null
        
        fun getInstance(context: Context): GroupMemberResolver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GroupMemberResolver(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
