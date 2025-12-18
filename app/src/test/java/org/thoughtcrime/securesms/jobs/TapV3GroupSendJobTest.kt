package org.thoughtcrime.securesms.jobs

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.RecipientTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.recipients.RecipientUtil
import org.thoughtcrime.securesms.tapv3.integration.TapV3SendIntegrator
import org.thoughtcrime.securesms.tapv3.integration.TapV3SendIntegrator.GroupSendResult
import org.thoughtcrime.securesms.tapv3.integration.TapV3SendIntegrator.TransportMethod
import org.whispersystems.signalservice.api.SignalServiceMessageSender
import org.whispersystems.signalservice.api.push.DistributionId
import java.util.Optional
import java.util.UUID

class TapV3GroupSendJobTest {

    private val context = mockk<Context>(relaxed = true)
    private val jobManager = mockk<JobManager>(relaxed = true)
    private val messageSender = mockk<SignalServiceMessageSender>(relaxed = true)
    private val sendIntegrator = mockk<TapV3SendIntegrator>(relaxed = true)
    
    // Database mocks
    private val messageTable = mockk<MessageTable>(relaxed = true)
    private val groupTable = mockk<GroupTable>(relaxed = true)
    private val recipientTable = mockk<RecipientTable>(relaxed = true)
    private val groupReceiptTable = mockk<GroupReceiptTable>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(AppDependencies)
        every { AppDependencies.getJobManager() } returns jobManager
        every { AppDependencies.getSignalServiceMessageSender() } returns messageSender
        every { AppDependencies.getSignalServiceAccountManager().serviceId } returns UUID.randomUUID()
        
        mockkObject(SignalDatabase)
        every { SignalDatabase.messages() } returns messageTable
        every { SignalDatabase.groups() } returns groupTable
        every { SignalDatabase.recipients() } returns recipientTable
        every { SignalDatabase.groupReceipts() } returns groupReceiptTable
        
        mockkStatic(TapV3SendIntegrator::class)
        every { TapV3SendIntegrator.getInstance(any()) } returns sendIntegrator
        
        mockkStatic(Recipient::class)
        mockkStatic(RecipientUtil::class)
        
        // Mock PushGroupSendJob static methods if possible, or ensure they don't crash
        // PushGroupSendJob.processGroupMessageResults is static void.
        // We can't easily mock static void method in Java class with mockk without some effort.
        // But since we mocked the database, it should run fine.
    }

    @Test
    fun `test group send fan-out logic with 5 members`() {
        // Setup data
        val messageId = 123L
        val groupIdString = "group_v3_id"
        val recipientId = RecipientId.from(1)
        val distributionId = DistributionId.from(UUID.randomUUID())
        
        val groupRecipient = mockk<Recipient>(relaxed = true)
        every { groupRecipient.isPushGroup } returns true
        val groupIdObj = mockk<GroupId>(relaxed = true)
        every { groupIdObj.requirePush().toString() } returns groupIdString
        every { groupRecipient.requireGroupId() } returns groupIdObj
        every { groupRecipient.id } returns recipientId
        every { groupRecipient.resolve() } returns groupRecipient
        
        val messageRecord = mockk<MmsMessageRecord>(relaxed = true)
        every { messageRecord.threadRecipient } returns groupRecipient
        every { messageRecord.id } returns messageId
        every { messageRecord.sentTimeMillis } returns System.currentTimeMillis()
        every { messageRecord.body } returns "Test message"
        every { messageRecord.expiresIn } returns 0
        every { messageRecord.isViewOnce } returns false
        every { messageRecord.mentions } returns emptyList()
        every { messageRecord.attachments } returns emptyList()
        every { messageRecord.networkFailures } returns emptySet()
        every { messageRecord.identityKeyMismatches } returns emptySet()
        
        every { messageTable.getOutgoingMessage(messageId) } returns messageRecord
        every { messageTable.getMessageRecord(messageId) } returns messageRecord
        every { messageTable.isSent(messageId) } returns false
        
        val groupRecord = mockk<GroupRecord>(relaxed = true)
        every { groupRecord.distributionId } returns distributionId
        every { groupTable.getGroup(any<GroupId>()) } returns Optional.of(groupRecord)
        
        // Mock members (5 people)
        val members = (1..5).map { 
             val r = mockk<Recipient>(relaxed = true)
             val uuid = UUID.randomUUID()
             every { r.requireServiceId().toString() } returns uuid.toString()
             every { r.serviceId } returns Optional.of(org.whispersystems.signalservice.api.push.ServiceId.from(uuid))
             every { r.e164 } returns Optional.empty()
             every { r.id } returns RecipientId.from(it.toLong() + 10)
             every { r.resolve() } returns r
             r
        }
        
        every { groupReceiptTable.getGroupReceiptInfo(messageId) } returns emptyList()
        every { groupTable.getGroupMembers(any(), any<GroupTable.MemberSet>()) } returns members
        every { RecipientUtil.getEligibleForSending(any()) } returns members
        
        // Mock encryption
        every { messageSender.getEncryptedBytesForTapV3(any(), any()) } returns ByteArray(10)
        
        // Mock SendIntegrator result
        coEvery { sendIntegrator.sendGroupMessage(any(), any(), any(), any()) } returns GroupSendResult(
            successCount = 5,
            failureCount = 0,
            failedMembers = emptyMap(),
            transportMethod = TransportMethod.IPFS
        )
        
        // Create Job
        val job = TapV3GroupSendJob(messageId, recipientId, emptySet(), false)
        job.setContext(context)
        
        // Execute
        job.onPushSend()
        
        // Verify
        coVerify { 
            sendIntegrator.sendGroupMessage(
                eq(groupIdString),
                match { it.size == 5 }, // Verify fan-out list size
                any(),
                any()
            )
        }
        
        // Since we cannot mock PushGroupSendJob.processGroupMessageResults easily, 
        // we check if the database markAsSent was called, which processGroupMessageResults does.
        verify { messageTable.markAsSent(messageId, true) }
    }
}
