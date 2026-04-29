package com.manjugroups.m_connect.network

import com.google.gson.annotations.SerializedName
import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface ApiService {

    // Auth
    @POST("api/auth/send-otp")
    suspend fun sendOtp(@Body body: SendOtpRequest): SendOtpResponse

    @POST("api/auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpRequest): VerifyOtpResponse

    @GET("api/auth/validate-session")
    suspend fun validateSession(@Header("Authorization") token: String): ValidateSessionResponse

    @POST("api/auth/logout")
    suspend fun logout(@Header("Authorization") token: String): LogoutResponse

    // Staff
    @GET("api/hr/staff")
    suspend fun getStaff(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null
    ): StaffListResponse

    @GET("api/hr/staff/search")
    suspend fun searchStaff(
        @Header("Authorization") token: String,
        @Query("query") query: String
    ): StaffListResponse

    // Attendance
    @GET("api/hr/attendance/today")
    suspend fun getMyAttendanceToday(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null
    ): AttendanceTodayResponse

    @GET("api/hr/attendance/day-sessions")
    suspend fun getDaySessions(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null
    ): DaySessionsResponse

    @GET("api/hr/attendance/my")
    suspend fun getMyAttendance(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null
    ): MyAttendanceResponse

    @POST("api/hr/attendance/punch-in")
    suspend fun punchIn(
        @Header("Authorization") token: String,
        @Body body: PunchRequest
    ): PunchResponse

    @POST("api/hr/attendance/punch-out")
    suspend fun punchOut(
        @Header("Authorization") token: String,
        @Body body: PunchRequest
    ): PunchResponse

    // Storage
    @POST("api/storage/generate-upload-url")
    suspend fun generateUploadUrl(@Header("Authorization") token: String): UploadUrlResponse

    @GET("api/storage/get-url")
    suspend fun getStorageUrl(
        @Header("Authorization") token: String,
        @Query("storageId") storageId: String
    ): StorageUrlResponse

    @POST("api/storage/upload")
    suspend fun uploadStorageFile(
        @Header("Authorization") token: String,
        @Body body: RequestBody
    ): StorageUploadResponse

    // Leaves
    @GET("api/hr/leaves/balance")
    suspend fun getLeaveBalance(
        @Header("Authorization") token: String,
        @Query("year") year: String? = null
    ): LeaveBalanceResponse

    @GET("api/hr/leaves/my")
    suspend fun getMyLeaves(@Header("Authorization") token: String): MyLeavesResponse

    @GET("api/hr/leaves/pending-approvals")
    suspend fun getPendingLeaveApprovals(@Header("Authorization") token: String): MyLeavesResponse

    @POST("api/hr/leaves/apply")
    suspend fun applyLeave(
        @Header("Authorization") token: String,
        @Body body: ApplyLeaveRequest
    ): ApplyLeaveResponse

    @POST("api/hr/leaves/approve")
    suspend fun approveLeave(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    @POST("api/hr/leaves/reject")
    suspend fun rejectLeave(
        @Header("Authorization") token: String,
        @Body body: RejectRequest
    ): SimpleResponse

    @POST("api/hr/leaves/cancel")
    suspend fun cancelLeave(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    // Permissions
    @GET("api/hr/permissions/monthly-usage")
    suspend fun getPermissionUsage(
        @Header("Authorization") token: String,
        @Query("year") year: String? = null,
        @Query("month") month: String? = null
    ): PermissionUsageResponse

    @GET("api/hr/permissions")
    suspend fun getMyPermissions(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null
    ): MyPermissionsResponse

    @GET("api/hr/permissions/pending-approvals")
    suspend fun getPendingPermissionApprovals(@Header("Authorization") token: String): MyPermissionsResponse

    @POST("api/hr/permissions/apply")
    suspend fun applyPermission(
        @Header("Authorization") token: String,
        @Body body: ApplyPermissionRequest
    ): ApplyPermissionResponse

    @POST("api/hr/permissions/approve")
    suspend fun approvePermission(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    @POST("api/hr/permissions/reject")
    suspend fun rejectPermission(
        @Header("Authorization") token: String,
        @Body body: RejectRequest
    ): SimpleResponse

    @POST("api/hr/permissions/cancel")
    suspend fun cancelPermission(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    // Push notifications
    @POST("api/push/register")
    suspend fun registerPushDevice(
        @Header("Authorization") token: String,
        @Body body: PushRegisterRequest
    ): PushRegisterResponse

    @POST("api/push/unregister")
    suspend fun unregisterPushDevice(
        @Header("Authorization") token: String,
        @Body body: PushUnregisterRequest
    ): SimpleResponse

    // Notifications
    @GET("api/notifications/unread-count")
    suspend fun getUnreadNotificationCount(
        @Header("Authorization") token: String
    ): NotificationUnreadCountResponse

    @GET("api/notifications")
    suspend fun getNotifications(
        @Header("Authorization") token: String
    ): NotificationsResponse

    @POST("api/notifications/mark-read")
    suspend fun markNotificationRead(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    @POST("api/notifications/mark-all-read")
    suspend fun markAllNotificationsRead(
        @Header("Authorization") token: String
    ): SimpleResponse

    // IAM
    @GET("api/iam/my-permissions")
    suspend fun getMyIamPermissions(@Header("Authorization") token: String): IamPermissionsResponse

    // Policy
    @GET("api/hr/policy")
    suspend fun getPolicy(@Header("Authorization") token: String): PolicyResponse

    // Staff paginated
    @GET("api/hr/staff/paginated")
    suspend fun getStaffPaginated(
        @Header("Authorization") token: String,
        @Query("numItems") numItems: Int = 25,
        @Query("cursor") cursor: String? = null,
        @Query("status") status: String? = null
    ): StaffPaginatedResponse

    @GET("api/hr/staff/count")
    suspend fun getStaffCount(@Header("Authorization") token: String): StaffCountResponse

    @GET("api/hr/staff/get")
    suspend fun getStaffDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): StaffDetailResponse

    // Chat - Channels
    @GET("api/chat/channels")
    suspend fun getChannels(@Header("Authorization") token: String): ChannelsResponse

    @GET("api/chat/channels/get")
    suspend fun getChannel(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String
    ): ChannelDetailResponse

    @POST("api/chat/channels/create")
    suspend fun createChannel(
        @Header("Authorization") token: String,
        @Body body: CreateChannelRequest
    ): CreateChannelResponse

    @GET("api/chat/channels/public")
    suspend fun getPublicChannels(@Header("Authorization") token: String): ChannelsResponse

    @POST("api/chat/channels/join")
    suspend fun joinChannel(
        @Header("Authorization") token: String,
        @Body body: ChannelIdRequest
    ): SimpleResponse

    // Chat - Conversations (DMs)
    @GET("api/chat/conversations")
    suspend fun getConversations(@Header("Authorization") token: String): ConversationsResponse

    @GET("api/chat/conversations/get")
    suspend fun getConversation(
        @Header("Authorization") token: String,
        @Query("conversationId") conversationId: String
    ): ConversationDetailResponse

    @POST("api/chat/conversations/dm")
    suspend fun startDm(
        @Header("Authorization") token: String,
        @Body body: StartDmRequest
    ): StartDmResponse

    @POST("api/chat/conversations/group-dm")
    suspend fun createGroupConversation(
        @Header("Authorization") token: String,
        @Body body: CreateGroupConversationRequest
    ): StartDmResponse

    // Chat - Messages
    @GET("api/chat/messages/channel")
    suspend fun getChannelMessages(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String,
        @Query("numItems") numItems: Int = 50,
        @Query("cursor") cursor: String? = null
    ): MessagesResponse

    @GET("api/chat/messages/conversation")
    suspend fun getConversationMessages(
        @Header("Authorization") token: String,
        @Query("conversationId") conversationId: String,
        @Query("numItems") numItems: Int = 50,
        @Query("cursor") cursor: String? = null
    ): MessagesResponse

    @GET("api/chat/messages/poll")
    suspend fun pollMessages(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String? = null,
        @Query("conversationId") conversationId: String? = null,
        @Query("after") after: Double
    ): PollMessagesResponse

    @POST("api/chat/messages/send")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Body body: SendMessageRequest
    ): SendMessageResponse

    @POST("api/chat/messages/mark-channel-read")
    suspend fun markChannelRead(
        @Header("Authorization") token: String,
        @Body body: ChannelIdRequest
    ): SimpleResponse

    @POST("api/chat/messages/mark-conversation-read")
    suspend fun markConversationRead(
        @Header("Authorization") token: String,
        @Body body: ConversationIdRequest
    ): SimpleResponse

    @POST("api/chat/typing")
    suspend fun setTyping(
        @Header("Authorization") token: String,
        @Body body: TypingRequest
    ): SimpleResponse

    @GET("api/chat/typing")
    suspend fun getTyping(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String? = null,
        @Query("conversationId") conversationId: String? = null
    ): TypingResponse

    @GET("api/chat/messages/search")
    suspend fun searchMessages(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("channelId") channelId: String? = null,
        @Query("conversationId") conversationId: String? = null
    ): ChatSearchResponse

    @GET("api/chat/messages/attachments")
    suspend fun listChatAttachments(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String? = null,
        @Query("conversationId") conversationId: String? = null
    ): ChatAttachmentsResponse

    @POST("api/staff/me/update")
    suspend fun updateMyProfile(
        @Header("Authorization") token: String,
        @Body body: UpdateMyProfileRequest
    ): UpdateMyProfileResponse

    // ── Project Management: My Tasks ──

    @GET("api/tasks/my")
    suspend fun getMyTasks(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null
    ): MyTasksResponse

    @GET("api/tasks/my/summary")
    suspend fun getMyTasksSummary(
        @Header("Authorization") token: String
    ): MyTaskSummaryResponse

    @GET("api/projects/tasks/get")
    suspend fun getTaskDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): TaskDetailResponse

    @POST("api/projects/tasks/update-progress")
    suspend fun updateTaskProgress(
        @Header("Authorization") token: String,
        @Body body: UpdateTaskProgressRequest
    ): TaskMutationResponse

    @POST("api/projects/tasks/update")
    suspend fun updateTask(
        @Header("Authorization") token: String,
        @Body body: UpdateTaskRequest
    ): TaskMutationResponse

    @POST("api/projects/tasks/add-update")
    suspend fun addTaskUpdate(
        @Header("Authorization") token: String,
        @Body body: AddTaskUpdateRequest
    ): TaskMutationResponse

    // ── Telecaller (mobile My Leads + Dialer) ──

    @GET("api/telecaller/leads/my")
    suspend fun getMyLeads(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int? = null
    ): MyLeadsResponse

    // Doocti click-to-call. The endpoint lives on the Next.js admin host
    // (mms.aivida.in/api/doocti-call), not Convex, so we pass the full URL.
    @POST
    suspend fun dialDoocti(
        @Url url: String,
        @Body body: DialDooctiRequest,
    ): DialDooctiResponse

    // ── Marketing: Projects + Inventory Units (KOS-52) ──────────────────────
    @GET("api/marketing/projects")
    suspend fun getMarketingProjects(
        @Header("Authorization") token: String,
    ): MarketingProjectsResponse

    @GET("api/marketing/inventory-units")
    suspend fun listInventoryUnits(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
        @Query("unitType") unitType: String? = null,
        @Query("facing") facing: String? = null,
        @Query("status") status: String? = null,
    ): InventoryUnitsResponse

    @GET("api/marketing/inventory-units/get")
    suspend fun getInventoryUnit(
        @Header("Authorization") token: String,
        @Query("id") id: String,
    ): InventoryUnitResponse

    @GET("api/marketing/inventory-units/layout")
    suspend fun getInventoryLayout(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
        @Query("layoutId") layoutId: String? = null,
    ): InventoryLayoutResponse

    @POST("api/marketing/inventory-units/hold")
    suspend fun holdInventoryUnit(
        @Header("Authorization") token: String,
        @Body body: InventoryUnitIdRequest,
    ): InventoryUnitResponse

    @POST("api/marketing/inventory-units/release")
    suspend fun releaseInventoryUnit(
        @Header("Authorization") token: String,
        @Body body: InventoryUnitIdRequest,
    ): InventoryUnitResponse

    // ── Marketing: Bookings (KOS-52 — wires plotId always) ──────────────────
    @POST("api/bookings")
    suspend fun createBooking(
        @Header("Authorization") token: String,
        @Body body: CreateBookingRequest,
    ): CreateBookingResponse

    @GET("api/telecaller/leads/search-by-phone")
    suspend fun searchTelecallerLeadsByPhone(
        @Header("Authorization") token: String,
        @Query("phone") phone: String,
    ): TelecallerLeadSearchResponse

    companion object {
        fun create(): ApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BuildConfig.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}

// Auth models
data class SendOtpRequest(val phone: String)
data class SendOtpResponse(val success: Boolean, val message: String?)
data class VerifyOtpRequest(val phone: String, val otp: String)
data class VerifyOtpResponse(val success: Boolean, val token: String?, val user: UserInfo?, val error: String?)
data class UserInfo(
    @SerializedName("_id") val staffId: String? = null,
    val name: String? = null,
    val role: String? = null,
    val phone: String? = null,
    val geoTrackingEnabled: Boolean = false
)
data class ValidateSessionResponse(val success: Boolean, val user: UserInfo?)
data class LogoutResponse(val success: Boolean, val message: String?)

// Staff models
data class StaffListResponse(val success: Boolean, val total: Int?, val staff: List<StaffData> = emptyList())
data class StaffData(
    @SerializedName("_id") val id: String?,
    val name: String?,
    val phone: String?,
    val role: String?,
    val designation: String?,
    val status: String?,
    val employeeId: String?,
    val department: String?
)

// Attendance models
data class AttendanceTodayResponse(val success: Boolean, val attendance: AttendanceData?)
data class MyAttendanceResponse(val success: Boolean, val total: Int?, val records: List<AttendanceRecord> = emptyList())
data class AttendanceRecord(
    val date: String?,
    val status: String?,
    val totalMinutes: Int?,
    val approvedAttendance: String?,
    val punchInTime: String? = null,
    val punchOutTime: String? = null,
    val hasOpenSession: Boolean? = null,
    val sessions: List<SessionData>? = emptyList(),
)
data class AttendanceData(
    val totalMinutes: Int?,
    val hasOpenSession: Boolean?,
    val firstPunchIn: String?,
    val lastPunchOut: String?,
    val sessions: List<SessionData>?,
    val status: String?
)
data class SessionData(
    val punchInTime: String?,
    val punchOutTime: String?,
    val source: String?,
    val totalMinutes: Int?
)
data class DaySessionsResponse(
    val success: Boolean,
    val sessions: List<SessionData>? = emptyList(),
    val totalHours: Double? = null,
    val cumulativeMinutes: Int? = null,
    val sessionCount: Int? = null,
    val hasOpenSession: Boolean? = null,
    val firstPunchIn: String? = null,
    val lastPunchOut: String? = null,
)

// Punch models
data class PunchRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val photo: String? = null,
    val deviceId: String? = null,
    val source: String = "mobile",
    val remarks: String? = null
)
data class PunchResponse(
    val success: Boolean,
    val attendanceId: String?,
    val trackingBootstrap: TrackingBootstrapData? = null,
    val error: String? = null
)

// Storage models
data class UploadUrlResponse(val success: Boolean, val uploadUrl: String?)
data class StorageUrlResponse(val success: Boolean, val url: String?)
data class StorageUploadResponse(
    val success: Boolean,
    val storageId: String? = null,
    val error: String? = null
)

// Leave models
data class LeaveBalanceResponse(val success: Boolean, val balance: LeaveBalance?)
data class LeaveBalance(
    val casual: Int = 0,
    val casualUsed: Int = 0,
    val sick: Int = 0,
    val sickUsed: Int = 0,
    val earned: Int = 0,
    val earnedUsed: Int = 0
)

data class MyLeavesResponse(val success: Boolean, val total: Int?, val leaves: List<LeaveData> = emptyList())
data class LeaveData(
    @SerializedName("_id") val id: String?,
    val leaveId: String? = null,
    val staffName: String? = null,
    val leaveType: String?,
    val fromDate: String?,
    val toDate: String?,
    val reason: String?,
    val status: String?,
    @SerializedName("_creationTime") val createdAt: Double?
)
data class ApplyLeaveRequest(
    val leaveType: String,
    val fromDate: String,
    val toDate: String,
    val reason: String
)
data class ApplyLeaveResponse(val success: Boolean, val leaveId: String?, val error: String? = null)

// IAM models
data class IamPermissionsResponse(
    val success: Boolean,
    val permissions: List<String> = emptyList(),
    val role: String? = null,
    val isAdmin: Boolean = false
)

// Policy models
data class PolicyResponse(val success: Boolean, val policy: PolicyData?)
data class PolicyData(val leave: LeavePolicy?, val permission: PermissionPolicy?, val office: OfficePolicy?)
data class LeavePolicy(
    val casualPerYear: Int = 0,
    val sickPerYear: Int = 0,
    val earnedPerYear: Int = 0,
    val types: List<String> = emptyList()
)
data class PermissionPolicy(val monthlyLimitHours: Int = 0)
data class OfficePolicy(val startTime: String?, val endTime: String?, val workingHoursPerDay: Int?)

// Permission models
data class PermissionUsageResponse(
    val success: Boolean,
    val totalHours: Int? = null,
    val usedHours: Int? = null,
    val limitHours: Int? = null,
    val remainingHours: Int? = null,
    val count: Int? = null
)
data class MyPermissionsResponse(val success: Boolean, val total: Int?, val permissions: List<PermissionData> = emptyList())
data class PermissionData(
    @SerializedName("_id") val id: String?,
    val permissionId: String? = null,
    val staffName: String? = null,
    val date: String?,
    val fromTime: String?,
    val toTime: String?,
    val hours: Double? = null,
    val reason: String?,
    val status: String?,
    @SerializedName("_creationTime") val createdAt: Double?
)
data class ApplyPermissionRequest(
    val date: String,
    val fromTime: String,
    val toTime: String,
    val reason: String
)
data class ApplyPermissionResponse(val success: Boolean, val permissionId: String?, val error: String? = null)

// Common
data class IdRequest(val id: String)
data class RejectRequest(val id: String, val reason: String)
data class SimpleResponse(val success: Boolean, val error: String? = null)
data class PushRegisterRequest(
    val token: String,
    val platform: String,
    val provider: String,
    val bundleId: String,
    val appId: String,
    val appName: String,
    val deviceId: String
)
data class PushRegisterResponse(val success: Boolean, val deviceTokenId: String? = null, val error: String? = null)
data class PushUnregisterRequest(val token: String)
data class NotificationUnreadCountResponse(
    val success: Boolean,
    val unreadCount: Int = 0
)
data class NotificationsResponse(
    val success: Boolean,
    val total: Int = 0,
    val notifications: List<NotificationData> = emptyList()
)
data class NotificationData(
    @SerializedName("_id") val id: String?,
    val type: String?,
    val title: String?,
    val message: String?,
    val referenceId: String?,
    val referenceType: String?,
    val read: Boolean = false,
    val createdAt: String?,
    @SerializedName("_creationTime") val creationTime: Double? = null
)

// Paginated staff
data class StaffPaginatedResponse(
    val success: Boolean,
    val page: List<StaffData> = emptyList(),
    val isDone: Boolean = true,
    val continueCursor: String? = null
)
data class StaffCountResponse(
    val success: Boolean,
    val all: Int = 0,
    val active: Int = 0,
    val inactive: Int = 0
)

// Staff detail
data class StaffDetailResponse(val success: Boolean, val staff: StaffFullData?)
data class StaffFullData(
    @SerializedName("_id") val id: String?,
    val name: String?, val phone: String?, val email: String?,
    val role: String?, val designation: String?, val department: String?,
    val status: String?, val employeeId: String?,
    val company: String?, val branch: String?,
    val dateOfBirth: String?, val joiningDate: String?,
    val bloodGroup: String?, val address: String?, val city: String?,
    val state: String?, val pincode: String?,
    val aadhaarNumber: String?, val panNumber: String?,
    val bankName: String?, val accountNumber: String?, val branchName: String?, val ifscCode: String?,
    val emergencyContact: EmergencyContact?,
    // Personal extras
    val gender: String?, val maritalStatus: String?,
    val fatherName: String?, val motherName: String?,
    val religion: String?, val nationality: String?,
    val qualification: String?, val experienceYears: Int?,
    // Employment
    val reportingToName: String?, val roleLevel: Int?,
    // Documents
    val documents: List<StaffDocument>?,
    // Photo
    val photo: String?
)
data class StaffDocument(
    val docType: String?, val name: String?,
    val storageId: String?, val uploadedOn: String?
)
data class EmergencyContact(val name: String?, val phone: String?, val relation: String?)

// Chat models
data class ChannelsResponse(val success: Boolean, val channels: List<ChannelData> = emptyList())
data class ChannelData(
    @SerializedName("_id") val id: String?,
    val name: String?, val description: String?,
    val type: String?, val memberCount: Int?,
    val unreadCount: Int?, val slug: String?,
    val muted: Boolean?
)
data class ChannelDetailResponse(val success: Boolean, val channel: ChannelData?)
data class CreateChannelRequest(
    val name: String,
    val description: String? = null,
    val type: String,
    val memberIds: List<String>? = null
)
data class CreateChannelResponse(val success: Boolean, val channelId: String?)
data class ConversationsResponse(val success: Boolean, val conversations: List<ConversationData> = emptyList())
data class ConversationDetailResponse(val success: Boolean, val conversation: ConversationData?)
data class ConversationData(
    @SerializedName("_id") val id: String?,
    val displayName: String?, val type: String?,
    val unreadCount: Int?, val muted: Boolean?,
    val lastMessage: MessageData?,
    val lastMessageAt: Long?,
    val participants: List<ParticipantData>?
)
data class ParticipantData(@SerializedName("_id") val id: String?, val name: String?)
data class MessagesResponse(
    val success: Boolean,
    val page: List<MessageData>? = null,
    val messages: List<MessageData>? = null,
    val isDone: Boolean? = null,
    val continueCursor: String? = null
)
data class PollMessagesResponse(
    val success: Boolean,
    val messages: List<MessageData> = emptyList(),
    val count: Int = 0
)
data class MessageData(
    @SerializedName("_id") val id: String?,
    @SerializedName("_creationTime") val creationTime: Double?,
    val body: String?, val senderId: String?, val senderName: String?,
    val channelId: String?, val conversationId: String?,
    val isDeleted: Boolean?, val isEdited: Boolean?,
    val replyCount: Int?, val parentMessageId: String?,
    val attachments: List<MessageAttachmentData>? = null
)
data class MessageAttachmentData(
    @SerializedName("_id") val id: String? = null,
    val storageId: String?,
    val fileName: String?,
    val fileType: String?,
    val fileSize: Long? = null,
    val url: String? = null
)
data class SendMessageRequest(
    val channelId: String? = null,
    val conversationId: String? = null,
    val body: String,
    val parentMessageId: String? = null,
    val mentionedStaffIds: List<String>? = null,
    val attachments: List<MessageAttachmentUpload>? = null
)
data class MessageAttachmentUpload(
    val storageId: String,
    val fileName: String,
    val fileType: String,
    val fileSize: Long
)
data class SendMessageResponse(val success: Boolean, val messageId: String?)
data class StartDmRequest(val otherStaffId: String)
data class StartDmResponse(val success: Boolean, val conversationId: String?)
data class CreateGroupConversationRequest(
    val memberIds: List<String>,
    val name: String? = null
)
data class ChannelIdRequest(val channelId: String)
data class ConversationIdRequest(val conversationId: String)
data class TypingRequest(
    val channelId: String? = null,
    val conversationId: String? = null
)
data class TypingResponse(
    val success: Boolean,
    val typing: List<TypingIndicatorData> = emptyList()
)
data class TypingIndicatorData(
    @SerializedName("_id") val id: String?,
    val staffId: String?,
    val staffName: String?,
    val expiresAt: Long?
)

// ── Chat search + attachments ──────────────────────────────────────────────

data class ChatSearchMessage(
    @SerializedName("_id") val id: String?,
    val senderId: String?,
    val senderName: String?,
    val body: String?,
    @SerializedName("_creationTime") val sentAt: Double?
)

data class ChatSearchResponse(
    val success: Boolean,
    val total: Int = 0,
    val messages: List<ChatSearchMessage> = emptyList(),
    val error: String? = null
)

data class ChatAttachmentItem(
    @SerializedName("_id") val id: String?,
    val messageId: String?,
    val storageId: String?,
    val fileName: String?,
    val fileType: String?,
    val fileSize: Long?,
    val url: String?
)

data class ChatAttachmentMessage(
    val messageId: String?,
    val senderId: String?,
    val senderName: String?,
    val body: String?,
    val sentAt: Double?,
    val attachments: List<ChatAttachmentItem> = emptyList()
)

data class ChatAttachmentsResponse(
    val success: Boolean,
    val total: Int = 0,
    val messages: List<ChatAttachmentMessage> = emptyList(),
    val error: String? = null
)

// ── Staff self-edit (personal + family fields) ─────────────────────────────

data class UpdateMyProfileRequest(
    val name: String? = null,
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val maritalStatus: String? = null,
    val spouseName: String? = null,
    val fatherName: String? = null,
    val motherName: String? = null,
    val religion: String? = null,
    val nationality: String? = null,
    val bloodGroup: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val anniversary: String? = null,
    val emergencyContact: EmergencyContact? = null
)

data class UpdateMyProfileResponse(
    val success: Boolean,
    val staff: StaffFullData? = null,
    val error: String? = null
)

// ── Project Tasks (mobile My Tasks) ─────────────────────────────────────────

data class TaskData(
    @SerializedName("_id") val id: String,
    val taskId: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val name: String? = null,
    val description: String? = null,
    val workCategory: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val actualStartDate: String? = null,
    val actualEndDate: String? = null,
    val duration: Int? = null,
    val progress: Int? = null,
    val assignedToName: String? = null,
    val staffAssignedTo: String? = null,
    val achievedQuantity: Double? = null,
    val totalQuantity: Double? = null,
    val unit: String? = null,
    val todaysUpdate: String? = null,
    val blocker: String? = null,
    val tomorrowsPlan: String? = null,
    @SerializedName("_creationTime") val createdAt: Double? = null
)

data class MyTasksResponse(
    val success: Boolean,
    val total: Int? = null,
    val tasks: List<TaskData> = emptyList(),
    val error: String? = null
)

data class TaskSummaryData(
    val total: Int = 0,
    val notStarted: Int = 0,
    val inProgress: Int = 0,
    val completed: Int = 0,
    val delayed: Int = 0,
    val overallProgress: Int = 0
)

data class MyTaskSummaryResponse(
    val success: Boolean,
    val summary: TaskSummaryData? = null,
    val error: String? = null
)

data class TaskDetailResponse(
    val success: Boolean,
    val task: TaskData? = null,
    val error: String? = null
)

data class UpdateTaskProgressRequest(
    val id: String,
    val progress: Int,
    val actualStartDate: String? = null,
    val actualEndDate: String? = null
)

data class UpdateTaskRequest(
    val id: String,
    val status: String? = null,
    val progress: Int? = null,
    val actualStartDate: String? = null,
    val actualEndDate: String? = null,
    val achievedQuantity: Double? = null,
    val todaysUpdate: String? = null,
    val blocker: String? = null,
    val tomorrowsPlan: String? = null
)

data class AddTaskUpdateRequest(
    val taskId: String,
    val date: String? = null,
    val todaysUpdate: String? = null,
    val blocker: String? = null,
    val tomorrowsPlan: String? = null,
    val progressSnapshot: Int? = null
)

data class TaskMutationResponse(
    val success: Boolean,
    val updateId: String? = null,
    val task: TaskData? = null,
    val error: String? = null
)

// ── Telecaller leads (mobile) ──────────────────────────────────────────────

data class TelecallerLeadData(
    @SerializedName("_id") val id: String,
    val source: String? = null,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val mobileNumberNormalized: String? = null,
    val emailId: String? = null,
    val alternateNumber: String? = null,
    val campaignName: String? = null,
    val primaryCampaign: String? = null,
    val secondaryCampaign: String? = null,
    val assignedToStaffName: String? = null,
    val assignedDate: String? = null,
    val assignedTime: String? = null,
    val assignedDateTime: Double? = null,
    val leadReceivedAt: Double? = null,
    val followUpStatus: String? = null,
    val followUpRemarks: String? = null,
    val followUpDate: Double? = null,
    val asterCallStatus: String? = null,
    val callType: String? = null,
    val callDuration: Double? = null,
    val recordingUrl: String? = null,
    val transcriptionStatus: String? = null,
    val clientCity: String? = null,
    val locationPreferred: String? = null,
    val budget: String? = null,
    val isAutoClosed: Boolean? = null,
    @SerializedName("_creationTime") val createdAt: Double? = null
)

data class MyLeadsResponse(
    val success: Boolean,
    val total: Int? = null,
    val leads: List<TelecallerLeadData> = emptyList(),
    val error: String? = null
)

data class TelecallerLeadSearchResponse(
    val success: Boolean,
    val total: Int? = null,
    val leads: List<TelecallerLeadSearchData> = emptyList(),
    val error: String? = null
)

data class TelecallerLeadSearchData(
    @SerializedName("_id") val id: String,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val emailId: String? = null,
    val clientCity: String? = null,
    val locationPreferred: String? = null,
    val suggestedVisitAddress: String? = null,
    val latestAnalysisProfile: LeadAnalysisProfile? = null,
)

data class LeadAnalysisProfile(
    val clientName: String? = null,
    val pincode: String? = null,
    val address: String? = null,
    val landmark: String? = null,
    val state: String? = null,
    val district: String? = null,
    val alternateMobileNumber: String? = null,
    val propertyType: String? = null,
    val propertyInterest: LeadPropertyInterest? = null,
)

data class LeadPropertyInterest(
    val location: String? = null,
    val priceRangeLakhs: String? = null,
    val extentInSqft: String? = null,
)

data class DialDooctiRequest(
    val phone_number: String,
    val station: String? = null,
    val cli_number: String? = null,
    val agent: String? = null,
)

data class DialDooctiResponse(
    val ok: Boolean? = null,
    val data: Any? = null,
    val error: String? = null,
    val stage: String? = null,
    val status: Int? = null,
)

// ── Marketing: Projects + Inventory Units (KOS-52) ──────────────────────────
data class MarketingProject(
    @SerializedName("_id") val id: String,
    val name: String? = null,
    val scope: String? = null,
    val status: String? = null,
    val location: String? = null,
)

data class MarketingProjectsResponse(
    val success: Boolean,
    val projects: List<MarketingProject> = emptyList(),
    val error: String? = null,
)

data class InventoryLayoutCoordinates(
    val shape: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val rotation: Double? = null,
    val svgViewBox: String? = null,
    val points: List<LayoutPoint>? = null,
)

data class LayoutPoint(val x: Double, val y: Double)

data class InventoryUnit(
    @SerializedName("_id") val id: String,
    val projectId: String? = null,
    val unitNumber: String? = null,
    val unitType: String? = null,           // plot|villa|flat
    val facing: String? = null,             // N|E|S|W|NE|NW|SE|SW
    val area: Double? = null,
    val dimensions: String? = null,
    val floor: Int? = null,
    val block: String? = null,
    val priceSnapshot: Double? = null,
    val status: String,                     // available|held|booked|sold
    val rawStatus: String? = null,
    val reservedByBookingId: String? = null,
    val soldByBookingId: String? = null,
    val customerName: String? = null,
    val layoutId: String? = null,
    val layoutCoordinates: InventoryLayoutCoordinates? = null,
)

data class InventoryUnitsResponse(
    val success: Boolean,
    val units: List<InventoryUnit> = emptyList(),
    val error: String? = null,
)

data class InventoryUnitResponse(
    val success: Boolean,
    val unit: InventoryUnit? = null,
    val error: String? = null,
)

data class InventoryLayoutProject(
    @SerializedName("_id") val id: String,
    val name: String? = null,
    val scope: String? = null,
)

data class InventoryLayoutResponse(
    val success: Boolean,
    val project: InventoryLayoutProject? = null,
    val units: List<InventoryUnit> = emptyList(),
    val error: String? = null,
)

data class InventoryUnitIdRequest(val id: String)

// ── Marketing: Bookings (KOS-52) ────────────────────────────────────────────
// Mobile sends only the fields the booking picker collects. plotId is always
// included when the user selected an inventory unit — server-side validator
// (KOS-40) rejects mismatched project + sold/booked rows.
data class CreateBookingRequest(
    val clientName: String,
    val mobileNumber: String,
    val bookingDate: String,                // yyyy-MM-dd
    val leadId: String? = null,
    val projectId: String? = null,
    val plotId: String? = null,
    val plotNo: String? = null,
    val bookingType: String? = null,
    val bookingMode: String? = null,
    val bookingCost: Double? = null,
    val advanceAmount: Double? = null,
    val balanceAmount: Double? = null,
    val email: String? = null,
    val homeAddress: String? = null,
)

data class CreateBookingResponse(
    val success: Boolean,
    val id: String? = null,
    val error: String? = null,
)
