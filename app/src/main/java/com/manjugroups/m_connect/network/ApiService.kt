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

    @POST("api/auth/login-with-employee-id")
    suspend fun loginWithEmployeeId(@Body body: EmployeePasswordLoginRequest): EmployeePasswordLoginResponse

    @POST("api/auth/change-own-password")
    suspend fun changeOwnPassword(
        @Header("Authorization") token: String,
        @Body body: ChangeOwnPasswordRequest
    ): SimpleResponse

    @GET("api/auth/validate-session")
    suspend fun validateSession(@Header("Authorization") token: String): ValidateSessionResponse

    @GET
    suspend fun lookupPincode(@Url url: String): List<PincodeLookupEnvelope>

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

    // Fines & Deductions
    @GET("api/hr/fines/list")
    suspend fun listFines(
        @Header("Authorization") token: String,
        @Query("status") status: String? = "active",
        @Query("month") month: Int? = null,
        @Query("year") year: Int? = null,
    ): FinesListResponse

    // The caller's OWN fines (incl. attendance late fines) — view-only staff.
    @GET("api/hr/fines/my")
    suspend fun listMyFines(
        @Header("Authorization") token: String,
        @Query("month") month: Int? = null,
        @Query("year") year: Int? = null,
    ): FinesListResponse

    // ── Overview / Management Dashboard (not VP-only — served to anyone
    // granted the vpDashboard.view IAM key, which predates the rename) ──
    @GET("api/dashboard/overview")
    suspend fun getOverviewDashboard(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
    ): OverviewDashboardResponse

    @GET("api/mobile/dashboard")
    suspend fun getMobileDashboard(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
    ): MobileDashboardResponse

    @GET("api/mobile/dialer/config")
    suspend fun getMobileDialerConfig(
        @Header("Authorization") token: String,
    ): MobileDialerConfigResponse

    @GET("api/dashboard/calls")
    suspend fun getDashboardCalls(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
        @Query("direction") direction: String? = null,
    ): DashboardCallsResponse

    @GET("api/dashboard/registrations")
    suspend fun getDashboardRegistrations(
        @Header("Authorization") token: String,
        @Query("date") date: String? = null,
    ): DashboardRegistrationsResponse

    @POST("api/hr/fines/create")
    suspend fun createFine(
        @Header("Authorization") token: String,
        @Body body: CreateFineRequest,
    ): CreateFineResponse

    // Issues (project-scoped, raised from the app)
    @POST("api/projects/issues")
    suspend fun createProjectIssue(
        @Header("Authorization") token: String,
        @Body body: CreateProjectIssueRequest,
    ): CreateIssueResponse

    @GET("api/issues/my")
    suspend fun listMyIssues(
        @Header("Authorization") token: String,
    ): IssuesListResponse

    // Front Desk: resolve a scanned invite QR token to its visitor details.
    @GET("api/frontdesk/invitations/by-token")
    suspend fun getInvitationByToken(
        @Header("Authorization") token: String,
        @Query("token") inviteToken: String,
    ): InvitationLookupResponse

    // Front Desk: admit (check in) the scanned invitation.
    @POST("api/frontdesk/invitations/checkin")
    suspend fun checkinInvitation(
        @Header("Authorization") token: String,
        @Body body: CheckinInvitationRequest,
    ): CheckinInvitationResponse

    // Front Desk: re-scan a checked-in invitation and mark the visitor leaving.
    @POST("api/frontdesk/invitations/checkout")
    suspend fun checkoutInvitation(
        @Header("Authorization") token: String,
        @Body body: CheckoutInvitationRequest,
    ): CheckoutInvitationResponse

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

    // Home geofence policy for the authenticated staff. The app uses this
    // to disable the Clock-In button when the user is inside their home
    // radius, instead of waiting for the server to reject the punch.
    @GET("api/hr/attendance/home-fence")
    suspend fun getHomeFence(
        @Header("Authorization") token: String,
    ): HomeFenceResponse

    // Master vendors list — drives the On-Duty form's "Select Vendor"
    // step. Was previously a hardcoded sample list.
    @GET("api/library/vendors")
    suspend fun getVendors(
        @Header("Authorization") token: String,
    ): VendorsResponse

    // On-Duty trip lifecycle — creates / closes a free-standing geoTrips
    // row so the HR Attendance modal shows the on-duty session in its
    // Trips strip alongside site-visit trips (with status, distance and
    // route polyline derived from the locationPoints stream).
    @POST("api/geotrack/on-duty/start")
    suspend fun startOnDutyTrip(
        @Header("Authorization") token: String,
        @Body body: StartOnDutyTripRequest,
    ): StartOnDutyTripResponse

    @POST("api/geotrack/on-duty/complete")
    suspend fun completeOnDutyTrip(
        @Header("Authorization") token: String,
        @Body body: CompleteOnDutyTripRequest,
    ): CompleteOnDutyTripResponse

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

    /**
     * Withdraw a pending attendance submission for a specific date.
     * Mirrors /api/hr/leaves/cancel — same delete affordance on the
     * mobile attendance history page. Server rejects non-pending dates.
     */
    /**
     * Raise an attendance correction / remark request for HR approval.
     * Mirrors the web attendance "Request Time Correction / Remark" dialog
     * (attendanceRequests.submit). Used by the mobile Edit Attendance
     * sheet to request a punch-timing update on a past day.
     */
    @POST("api/hr/attendance/request")
    suspend fun submitAttendanceRequest(
        @Header("Authorization") token: String,
        @Body body: AttendanceRequestBody
    ): AttendanceRequestResponse

    @POST("api/hr/attendance/cancel")
    suspend fun cancelMyAttendance(
        @Header("Authorization") token: String,
        @Body body: AttendanceCancelRequest,
    ): SimpleResponse

    // Attendance — manager approval queue (mirrors leaves/permissions approval).
    // scope = "direct" (My Team) | "subtree" (All Team); onlyOverdue = Blocking
    // reviews; all = company-wide "All Approval" (needs permission, else 403).
    // Null params fall back to the server default (direct reports).
    @GET("api/hr/attendance/pending-approvals")
    suspend fun getPendingAttendanceApprovals(
        @Header("Authorization") token: String,
        @Query("scope") scope: String? = null,
        @Query("onlyOverdue") onlyOverdue: Boolean? = null,
        @Query("all") all: Boolean? = null,
        // requests=true also returns the caller's team correction/remark
        // requests waiting on the reporting officer (response.requests).
        @Query("requests") requests: Boolean? = null,
    ): AttendanceApprovalsResponse

    // Team Attendance tab — the caller's reporting-subtree attendance for a
    // date range. Records fit AttendanceApprovalRecord (staff/date/punch/status).
    @GET("api/hr/attendance/team-attendance")
    suspend fun getTeamAttendance(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
    ): AttendanceApprovalsResponse

    // Company-wide attendance for the "All" tab — mirrors the web Attendance
    // page's "All" view (staffAttendance.listForReport). Gated on
    // attendance.viewAll server-side.
    @GET("api/hr/attendance/all")
    suspend fun getAllAttendance(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
        // Server-side name/employee-id search. Without it the backend returns
        // only the newest ~750 rows company-wide (≈ a few days), so client-side
        // filtering silently missed a person's older records.
        @Query("search") search: String? = null,
    ): AttendanceApprovalsResponse

    // Whether the caller has a team (direct reports) — drives which attendance
    // tabs show + the "No team members" empty state, mirroring the web.
    // Put an attendance row on hold (web parity: the amber Hold action in the
    // review dialog). Body reuses RejectRequest — {id, reason}.
    @POST("api/hr/attendance/hold")
    suspend fun holdAttendance(
        @Header("Authorization") token: String,
        @Body body: RejectRequest,
    ): SimpleResponse

    @GET("api/hr/attendance/team-scope")
    suspend fun getAttendanceTeamScope(
        @Header("Authorization") token: String,
    ): TeamScopeResponse

    // HR Review tab — company-wide rows awaiting HR action (needs permission).
    @GET("api/hr/attendance/hr-review")
    suspend fun getHrReview(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String,
    ): AttendanceApprovalsResponse

    @POST("api/hr/attendance/approve")
    suspend fun approveAttendance(
        @Header("Authorization") token: String,
        @Body body: ApproveAttendanceRequest
    ): SimpleResponse

    @POST("api/hr/attendance/reject")
    suspend fun rejectAttendance(
        @Header("Authorization") token: String,
        @Body body: RejectRequest
    ): SimpleResponse

    // Shifts
    @GET("api/hr/shifts/today")
    suspend fun getTodayShift(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String,
        @Query("date") date: String? = null
    ): TodayShiftResponse

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

    // ── Staff digital signature (reused for loan e-sign) ──
    @GET("api/hr/staff/digital-sign")
    suspend fun getDigitalSign(
        @Header("Authorization") token: String
    ): DigitalSignResponse

    @POST("api/hr/staff/digital-sign")
    suspend fun saveDigitalSign(
        @Header("Authorization") token: String,
        @Body body: DigitalSignRequest
    ): DigitalSignResponse

    // Leaves
    @GET("api/hr/leaves/balance")
    suspend fun getLeaveBalance(
        @Header("Authorization") token: String,
        @Query("year") year: String? = null
    ): LeaveBalanceResponse

    @GET("api/hr/leaves/my")
    suspend fun getMyLeaves(@Header("Authorization") token: String): MyLeavesResponse

    @GET("api/hr/leaves/pending-approvals")
    suspend fun getPendingLeaveApprovals(
        @Header("Authorization") token: String,
        @Query("teamOnly") teamOnly: Boolean? = null,
        @Query("scope") scope: String? = null,
    ): MyLeavesResponse

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

    // Comp Off — the credit pool that backs Compensatory Off leave.
    @GET("api/hr/compoff/credits")
    suspend fun getCompOffCredits(@Header("Authorization") token: String): CompOffCreditsResponse

    @POST("api/hr/compoff/apply")
    suspend fun applyCompOff(
        @Header("Authorization") token: String,
        @Body body: ApplyCompOffRequest
    ): ApplyCompOffResponse

    @POST("api/hr/staff/me/profile-photo")
    suspend fun setMyProfilePhoto(
        @Header("Authorization") token: String,
        @Body body: SetProfilePhotoRequest
    ): SetProfilePhotoResponse

    @retrofit2.http.DELETE("api/hr/staff/me/profile-photo")
    suspend fun deleteMyProfilePhoto(
        @Header("Authorization") token: String
    ): SimpleResponse

    // ── Loans (read-only) ──
    @GET("api/hr/loans/my")
    suspend fun getMyLoans(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null
    ): MyLoansResponse

    @GET("api/hr/loans/get")
    suspend fun getLoanDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): LoanDetailResponse

    @GET("api/hr/loans/repayments")
    suspend fun getLoanRepayments(
        @Header("Authorization") token: String,
        @Query("loanId") loanId: String
    ): LoanRepaymentsResponse

    @POST("api/hr/loans/apply")
    suspend fun applyLoan(
        @Header("Authorization") token: String,
        @Body body: ApplyLoanRequest
    ): ApplyLoanResponse

    @POST("api/hr/loans/cancel")
    suspend fun cancelLoan(
        @Header("Authorization") token: String,
        @Body body: IdRequest
    ): SimpleResponse

    @GET("api/hr/loans/pending-approvals")
    suspend fun getPendingLoanApprovals(@Header("Authorization") token: String): MyLoansResponse

    // Configured Approval Workflow (Settings → Workflows) run + resolved steps
    // for a loan/advance. `workflow` is null when the row is on the legacy
    // code-stage path (no workflow configured at creation time).
    @GET("api/hr/loans/workflow")
    suspend fun getLoanWorkflow(
        @Header("Authorization") token: String,
        @Query("loanId") loanId: String
    ): LoanWorkflowResponse

    @POST("api/hr/loans/approve")
    suspend fun approveLoan(
        @Header("Authorization") token: String,
        @Body body: ApproveLoanRequest
    ): SimpleResponse

    @POST("api/hr/loans/reject")
    suspend fun rejectLoan(
        @Header("Authorization") token: String,
        @Body body: RejectRequest
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
    suspend fun getPendingPermissionApprovals(
        @Header("Authorization") token: String,
        // scope=direct keeps Team Permission limited to the caller's direct
        // reports. all=true returns every request company-wide when permitted.
        @Query("scope") scope: String? = null,
        @Query("all") all: Boolean? = null,
    ): MyPermissionsResponse

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

    // Chat - Channel management
    @POST("api/chat/channels/leave")
    suspend fun leaveChannel(
        @Header("Authorization") token: String,
        @Body body: ChannelIdRequest
    ): SimpleResponse

    @POST("api/chat/channels/update")
    suspend fun updateChannel(
        @Header("Authorization") token: String,
        @Body body: UpdateChannelRequest
    ): SimpleResponse

    @POST("api/chat/channels/archive")
    suspend fun archiveChannel(
        @Header("Authorization") token: String,
        @Body body: ChannelIdRequest
    ): SimpleResponse

    // Hard delete — creator-only, and only once every other member has left.
    @POST("api/chat/channels/delete")
    suspend fun deleteChannel(
        @Header("Authorization") token: String,
        @Body body: ChannelIdRequest
    ): SimpleResponse

    @POST("api/chat/channels/add-member")
    suspend fun addChannelMember(
        @Header("Authorization") token: String,
        @Body body: ChannelMemberRequest
    ): SimpleResponse

    @POST("api/chat/channels/remove-member")
    suspend fun removeChannelMember(
        @Header("Authorization") token: String,
        @Body body: ChannelMemberRequest
    ): SimpleResponse

    @POST("api/chat/channels/set-mute")
    suspend fun setChannelMute(
        @Header("Authorization") token: String,
        @Body body: SetMuteRequest
    ): SimpleResponse

    @POST("api/chat/channels/set-role")
    suspend fun setChannelRole(
        @Header("Authorization") token: String,
        @Body body: SetChannelRoleRequest
    ): SimpleResponse

    @GET("api/chat/channels/search")
    suspend fun searchChannels(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("limit") limit: Int = 25
    ): ChannelsResponse

    @GET("api/chat/channels/members")
    suspend fun getChannelMembers(
        @Header("Authorization") token: String,
        @Query("channelId") channelId: String
    ): ChannelMembersResponse

    // Chat - Conversation management
    @POST("api/chat/conversations/add-member")
    suspend fun addConversationMember(
        @Header("Authorization") token: String,
        @Body body: ConversationMemberRequest
    ): SimpleResponse

    @POST("api/chat/conversations/remove-member")
    suspend fun removeConversationMember(
        @Header("Authorization") token: String,
        @Body body: ConversationMemberRequest
    ): SimpleResponse

    // Group-dm parity: rename/description + admin roles for legacy groups.
    @POST("api/chat/conversations/update")
    suspend fun updateGroupConversation(
        @Header("Authorization") token: String,
        @Body body: UpdateGroupConversationRequest
    ): SimpleResponse

    @POST("api/chat/conversations/set-role")
    suspend fun setConversationRole(
        @Header("Authorization") token: String,
        @Body body: SetConversationRoleRequest
    ): SimpleResponse

    @POST("api/chat/conversations/hide")
    suspend fun hideConversation(
        @Header("Authorization") token: String,
        @Body body: ConversationIdRequest
    ): SimpleResponse

    @POST("api/chat/conversations/set-mute")
    suspend fun setConversationMute(
        @Header("Authorization") token: String,
        @Body body: SetMuteRequest
    ): SimpleResponse

    // Chat - Message edit/delete/replies/unread
    @GET("api/chat/messages/replies")
    suspend fun getMessageReplies(
        @Header("Authorization") token: String,
        @Query("parentMessageId") parentMessageId: String
    ): MessagesResponse

    @GET("api/chat/messages/get")
    suspend fun getMessage(
        @Header("Authorization") token: String,
        @Query("messageId") messageId: String
    ): SingleMessageResponse

    @GET("api/chat/messages/unread-summary")
    suspend fun getUnreadSummary(
        @Header("Authorization") token: String
    ): UnreadSummaryResponse

    @POST("api/chat/messages/edit")
    suspend fun editMessage(
        @Header("Authorization") token: String,
        @Body body: EditMessageRequest
    ): SimpleResponse

    @POST("api/chat/messages/delete")
    suspend fun deleteMessage(
        @Header("Authorization") token: String,
        @Body body: DeleteMessageRequest
    ): SimpleResponse

    // Chat - Reactions
    @GET("api/chat/reactions")
    suspend fun getReactions(
        @Header("Authorization") token: String,
        @Query("messageId") messageId: String
    ): ReactionsResponse

    @POST("api/chat/reactions/add")
    suspend fun addReaction(
        @Header("Authorization") token: String,
        @Body body: ReactionRequest
    ): SimpleResponse

    @POST("api/chat/reactions/remove")
    suspend fun removeReaction(
        @Header("Authorization") token: String,
        @Body body: ReactionRequest
    ): SimpleResponse

    @POST("api/chat/reactions/toggle")
    suspend fun toggleReaction(
        @Header("Authorization") token: String,
        @Body body: ReactionRequest
    ): SimpleResponse

    // Chat - Presence
    @GET("api/chat/presence")
    suspend fun getPresence(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("staffIds") staffIds: String? = null
    ): PresenceResponse

    @GET("api/chat/presence/online")
    suspend fun getOnlineStaff(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 100
    ): PresenceListResponse

    @POST("api/chat/presence/heartbeat")
    suspend fun presenceHeartbeat(
        @Header("Authorization") token: String,
        @Body body: PresenceHeartbeatRequest
    ): SimpleResponse

    @POST("api/hr/staff/update")
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

    // ── Task Manager (daily tasks) — mirrors web app/task-manager/page.tsx ──

    @GET("api/dailyTasks/listForTaskManager")
    suspend fun getTaskManagerTasks(
        @Header("Authorization") token: String,
        @Query("today") today: String? = null
    ): TaskManagerResponse

    @POST("api/dailyTasks/updateStatus")
    suspend fun updateDailyTaskStatus(
        @Header("Authorization") token: String,
        @Body body: UpdateDailyTaskStatusRequest
    ): DailyTaskStatusResponse

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

    // ── Project Management: project list / detail / per-project tasks ──

    @GET("api/projects")
    suspend fun getMyProjects(
        @Header("Authorization") token: String
    ): MyProjectsResponse

    @GET("api/projects/get")
    suspend fun getProjectDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): ProjectDetailResponse

    @GET("api/projects/tasks")
    suspend fun getProjectTasks(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String
    ): ProjectTasksResponse

    @GET("api/projects/tasks/updates")
    suspend fun getTaskTimeline(
        @Header("Authorization") token: String,
        @Query("taskId") taskId: String
    ): TaskTimelineResponse

    @GET("api/projects/tasks/resources")
    suspend fun getTaskResources(
        @Header("Authorization") token: String,
        @Query("taskId") taskId: String
    ): TaskResourcesResponse

    // ── Storage: generate signed upload URL (Convex storage) ──

    @POST("api/storage/generate-upload-url")
    suspend fun generateStorageUploadUrl(
        @Header("Authorization") token: String
    ): StorageUploadUrlResponse

    // PUT the file bytes to the signed URL returned above. Convex returns
    // a JSON body with the storageId once the upload completes.
    @PUT
    suspend fun uploadFileToStorage(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Body body: okhttp3.RequestBody
    ): StorageUploadResultResponse

    // ── Project Expenses ──

    @GET("api/projects/expenses")
    suspend fun listProjectExpenses(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null,
        @Query("category") category: String? = null
    ): ProjectExpensesResponse

    @GET("api/projects/expenses/get")
    suspend fun getProjectExpense(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): ProjectExpenseDetailResponse

    @POST("api/projects/expenses/create")
    suspend fun createProjectExpense(
        @Header("Authorization") token: String,
        @Body body: CreateProjectExpenseRequest
    ): ProjectExpenseCreateResponse

    @POST("api/projects/expenses/update")
    suspend fun updateProjectExpense(
        @Header("Authorization") token: String,
        @Body body: UpdateProjectExpenseRequest
    ): SimpleResponse

    @POST("api/projects/expenses/mark-paid")
    suspend fun markProjectExpensePaid(
        @Header("Authorization") token: String,
        @Body body: MarkExpensePaidRequest
    ): SimpleResponse

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

    // Quick-create a project from the mobile daily-log picker (basics only;
    // full editing happens on web).
    @POST("api/marketing/projects/create")
    suspend fun createProject(
        @Header("Authorization") token: String,
        @Body body: CreateProjectRequest,
    ): CreateProjectResponse

    // Master material catalog (Project Management > Library > Material Catalog).
    @GET("api/materials")
    suspend fun getMaterials(
        @Header("Authorization") token: String,
    ): MaterialsResponse

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

    // ── Booking-form auto-save scratchpad. CompleteCpVisitBottomSheet
    // pushes the in-progress form state to /save on a debounced timer
    // so a crash / kill / re-login on a different phone doesn't lose
    // the operator's typing. Resumes the form via /get on dialog open
    // and wipes the row via /clear after a successful createBooking.
    @POST("api/bookings/draft/save")
    suspend fun saveBookingDraft(
        @Header("Authorization") token: String,
        @Body body: BookingDraftSaveRequest,
    ): BookingDraftSaveResponse

    @GET("api/bookings/draft/get")
    suspend fun getBookingDraft(
        @Header("Authorization") token: String,
        @Query("sourceKey") sourceKey: String,
    ): BookingDraftGetResponse

    @POST("api/bookings/draft/clear")
    suspend fun clearBookingDraft(
        @Header("Authorization") token: String,
        @Body body: BookingDraftClearRequest,
    ): BookingDraftClearResponse

    // GET /api/marketing/bookings/my — bookings the caller is involved in.
    // `status` is one of draft|pending_confirmation|confirmed|cancelled or
    // null for "All". Backend gates on marketing.bookings.view and returns
    // an empty list (200) when the user lacks permission.
    @GET("api/marketing/bookings/my")
    suspend fun listMyBookings(
        @Header("Authorization") token: String,
        @Query("status") status: String? = null,
    ): BookingsListResponse

    @GET("api/bookings/{id}")
    suspend fun getBooking(
        @Header("Authorization") token: String,
        @Path("id") id: String,
    ): BookingDetailResponse

    @PATCH("api/bookings/{id}")
    suspend fun updateBooking(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body body: UpdateBookingRequest,
    ): SimpleResponse

    @POST("api/bookings/{id}/approve")
    suspend fun approveBooking(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body body: BookingApproveRequest = BookingApproveRequest(),
    ): BookingActionResponse

    @POST("api/bookings/{id}/reject")
    suspend fun rejectBooking(
        @Header("Authorization") token: String,
        @Path("id") id: String,
        @Body body: BookingRejectRequest,
    ): BookingActionResponse

    @GET("api/bookings/plot-prefill")
    suspend fun getBookingPlotPrefill(
        @Header("Authorization") token: String,
        @Query("plotId") plotId: String,
        @Query("bookingDate") bookingDate: String? = null,
    ): BookingPlotPrefillResponse

    @GET("api/bookings/conversion-prefill")
    suspend fun getBookingConversionPrefill(
        @Header("Authorization") token: String,
        @Query("mobileNumber") mobileNumber: String,
    ): BookingConversionPrefillResponse

    @GET("api/bookings/exchange-source-candidates")
    suspend fun listBookingExchangeSourceCandidates(
        @Header("Authorization") token: String,
        @Query("mobileNumber") mobileNumber: String,
    ): BookingExchangeSourcesResponse

    @GET("api/telecaller/leads/search-by-phone")
    suspend fun searchTelecallerLeadsByPhone(
        @Header("Authorization") token: String,
        @Query("phone") phone: String,
    ): TelecallerLeadSearchResponse

    @GET("api/clients/search-by-phone")
    suspend fun searchClientByPhone(
        @Header("Authorization") token: String,
        @Query("phone") phone: String,
    ): ClientSearchResponse

    /**
     * Push edits made by the field staff on the prefilled client
     * form back to the lead's manualProfile. Server records the
     * caller as editorStaffId so the lead's edit-history timeline
     * picks up the change with proper attribution.
     */
    @POST("api/telecaller/leads/update")
    suspend fun updateTelecallerLead(
        @Header("Authorization") token: String,
        @Body body: UpdateTelecallerLeadRequest,
    ): SimpleResponse

    // ── Land Procurement: Inspection ────────────────────────────────────
    @GET("api/land/inspections/my")
    suspend fun listMyInspections(
        @Header("Authorization") token: String,
    ): InspectionListResponse

    @GET("api/land/inspections/get")
    suspend fun getInspectionForProperty(
        @Header("Authorization") token: String,
        @Query("propertyId") propertyId: String,
    ): InspectionGetResponse

    @POST("api/land/inspections/save")
    suspend fun saveInspection(
        @Header("Authorization") token: String,
        @Body body: InspectionSaveRequest,
    ): InspectionSaveResponse

    @POST("api/land/inspections/accept")
    suspend fun acceptInspection(
        @Header("Authorization") token: String,
        @Body body: InspectionAcceptRequest,
    ): InspectionActionResponse

    @POST("api/land/inspections/reschedule")
    suspend fun rescheduleInspection(
        @Header("Authorization") token: String,
        @Body body: InspectionRescheduleRequest,
    ): InspectionActionResponse

    @GET("api/land/queries/my")
    suspend fun listMyQueries(
        @Header("Authorization") token: String,
    ): QueryListResponse

    @POST("api/land/queries/update")
    suspend fun updateQuery(
        @Header("Authorization") token: String,
        @Body body: QueryUpdateRequest,
    ): InspectionActionResponse

    companion object {
        // Single shared client for the whole app. Previously every one of the
        // ~114 create() call sites built a fresh OkHttpClient (its own
        // connection + thread pools), so no TLS/keep-alive reuse across screens
        // — a real per-navigation latency hit. Now built once, lazily.
        private val instance: ApiService by lazy { buildService() }

        fun create(): ApiService = instance

        private fun buildService(): ApiService {
            val logging = HttpLoggingInterceptor().apply {
                // BODY logging buffers + logs full request/response bodies —
                // fine in debug, but pure overhead (and a data-leak risk) in
                // release. Gate it to debug builds only.
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
            }
            // Auto-logout on 401. Without this, a deployment URL swap
            // (dev → prod or vice versa) or a server-side session
            // revocation leaves the app stuck with every screen showing
            // empty / errored data and no way back to a working state.
            // The interceptor watches every response, fires the
            // SessionInvalidationBus on 401, and the currently-foreground
            // activity collects + bounces the user to login. Bearer-less
            // requests (auth/OTP/login endpoints) shouldn't normally 401
            // unless the token is invalid anyway, so we don't try to
            // distinguish — every 401 is treated as "session is dead."
            val authWatchdog = okhttp3.Interceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                // Skip the auto-logout when the outgoing request used the
                // dev bypass token — the server can't validate a synthetic
                // token so it will always 401, but the local dev session
                // is still legitimately "logged in" for UI exploration.
                // Without this, bypass users get kicked back to login on
                // the very first authed call after AuthBypass succeeds.
                if (response.code == 401 && !isBypassAuth(request)) {
                    com.manjugroups.m_connect.auth.SessionInvalidationBus
                        .reportUnauthorized()
                }
                response
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(authWatchdog)
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                // OkHttp's default write timeout is only 10s — a punch selfie
                // (~200-300KB) on a weak field uplink can't finish the body in
                // time, so uploads died with SocketTimeoutException. 60s only
                // bites on large request bodies; normal JSON calls are unaffected.
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BuildConfig.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }

        private fun isBypassAuth(request: okhttp3.Request): Boolean {
            val header = request.header("Authorization") ?: return false
            // Both "Bearer <token>" and bare "<token>" shapes turn up
            // in the codebase, so strip the prefix before comparing.
            val token = header.removePrefix("Bearer ").trim()
            return com.manjugroups.m_connect.auth.AuthBypass.isBypassToken(token)
        }
    }
}

// Auth models
data class SendOtpRequest(val phone: String)
data class SendOtpResponse(val success: Boolean, val message: String?)
data class VerifyOtpRequest(
    val phone: String,
    val otp: String,
    // Single-mobile-device binding telemetry. Null on the agency-driver /
    // fallback paths and on older flows; Gson omits nulls so the backend treats
    // them as absent (grace — no lockout). deviceId = Settings.Secure.ANDROID_ID.
    val deviceId: String? = null,
    val devicePlatform: String? = null,
    val deviceModel: String? = null,
    val batteryPct: Double? = null,
)
data class VerifyOtpResponse(val success: Boolean, val token: String?, val user: UserInfo?, val error: String?)
data class EmployeePasswordLoginRequest(
    val employeeId: String,
    val password: String,
    // Device-binding telemetry — the password login is locked to the same
    // device as the OTP login. Gson omits nulls (grace when unavailable).
    val deviceId: String? = null,
    val devicePlatform: String? = null,
    val deviceModel: String? = null,
    val batteryPct: Double? = null,
)
data class EmployeePasswordLoginResponse(
    val success: Boolean,
    val token: String? = null,
    val user: UserInfo? = null,
    val mustChangePassword: Boolean = false,
    val error: String? = null
)
data class ChangeOwnPasswordRequest(
    val currentPassword: String? = null,
    val newPassword: String
)
data class UserInfo(
    @SerializedName("_id") val staffId: String? = null,
    val employeeId: String? = null,
    val name: String? = null,
    val role: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val designation: String? = null,
    val department: String? = null,
    val isAdmin: Boolean = false,
    val roleLevel: Int? = null,
    val status: String? = null,
    val geoTrackingEnabled: Boolean = false,
    val mustChangePassword: Boolean = false,
    val canBill: Boolean = false,
)
data class ValidateSessionResponse(val success: Boolean, val user: UserInfo?)
data class PincodeLookupEnvelope(
    @SerializedName("Status") val status: String? = null,
    @SerializedName("PostOffice") val postOffice: List<PincodePostOffice>? = null
)
data class PincodePostOffice(
    @SerializedName("Name") val name: String? = null,
    @SerializedName("Block") val block: String? = null,
    @SerializedName("Division") val division: String? = null,
    @SerializedName("District") val district: String? = null,
    @SerializedName("State") val state: String? = null
)
data class LogoutResponse(val success: Boolean, val message: String?)

// Staff models
data class StaffListResponse(val success: Boolean, val total: Int?, val staff: List<StaffData> = emptyList())
data class StaffData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val name: String?,
    val phone: String?,
    val role: String?,
    val designation: String?,
    val status: String?,
    val employeeId: String?,
    val department: String?,
    val reportingTo: String? = null,
    val reportingToId: String? = null,
    val geoTrackingEnabled: Boolean = false,
    val trackingDeviceHealth: TrackingDeviceHealth? = null,
    val photo: String? = null
)

data class TrackingDeviceHealth(
    val status: String? = null,
    val lastSyncedAt: Long? = null,
    val missing: List<String> = emptyList(),
    val manufacturer: String? = null,
    val model: String? = null,
    val appVersion: String? = null,
)

// Attendance models
data class AttendanceTodayResponse(val success: Boolean, val attendance: AttendanceData?)
data class MyAttendanceResponse(val success: Boolean, val total: Int?, val records: List<AttendanceRecord> = emptyList())

// Mirrors getHomeFenceForStaff in convex/hr/staffHomeGeocoding.ts.
//   enabled     — true when ALL of: global toggle on + staff has lat/lng +
//                 geocode quality is enforceable. The app only blocks
//                 Clock-In if this is true.
//   enforceable — quality is enforceable on its own (true if the staff
//                 has a precise pin but the global toggle is off — useful
//                 if we ever want to show a soft warning instead of a
//                 block).
data class HomeFenceResponse(
    val success: Boolean,
    val fence: HomeFenceData? = null,
    val error: String? = null,
)

data class HomeFenceData(
    val enabled: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusMeters: Int = 150,
    val enforceable: Boolean = false,
)

// Vendor master data — mirrors /api/library/vendors. Slim subset of the
// convex vendors row: enough for the On-Duty form to render a list and
// (later) show the vendor's address on a map.
data class VendorsResponse(
    val success: Boolean,
    val total: Int = 0,
    val vendors: List<VendorRemote> = emptyList(),
    val error: String? = null,
)

data class VendorRemote(
    val id: String,
    val name: String,
    val nickname: String? = null,
    val companyName: String? = null,
    val type: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val status: String? = null,
)

// On-Duty trip lifecycle payloads. The server stamps a geoTrips row with
// status="active" on start and patches it to "completed" with distance
// computed from the locationPoints stream on complete.
data class StartOnDutyTripRequest(
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    val category: String? = null, // "Projects" | "Vendors" | "Others"
    val targetId: String? = null,
    val targetName: String? = null,
    val targetAddress: String? = null,
    val vehicleOwnership: String? = null, // "Own Vehicle" | "Office Vehicle"
    val vehicleType: String? = null,      // "2 Wheeler" | "4 Wheeler"
)

data class StartOnDutyTripResponse(
    val success: Boolean = false,
    val tripId: String? = null,
    val alreadyActive: Boolean = false,
    val error: String? = null,
)

data class CompleteOnDutyTripRequest(
    val tripId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val address: String? = null,
    // Proof-of-travel attachments. Required for Public Transport on-duty
    // (Rapido screenshot, bus ticket, etc.); null/empty for other vehicles.
    val proofPhotoIds: List<String>? = null,
    val proofNote: String? = null,
)

data class CompleteOnDutyTripResponse(
    val success: Boolean = false,
    val tripId: String? = null,
    val distanceMeters: Long? = null,
    val alreadyCompleted: Boolean = false,
    val reason: String? = null,
    val error: String? = null,
)

/** Body for /api/hr/attendance/cancel — withdraws a pending row by date. */
data class AttendanceCancelRequest(val date: String)

/**
 * Body for /api/hr/attendance/request. type is "remark" (just remark) or
 * "correction" (corrected punch in/out times + reason). The server derives
 * staffId/staffName from the bearer token, so they're not sent here.
 */
data class AttendanceRequestBody(
    // Null for a no-punch ("Absent") day with no attendance row yet — the
    // backend seeds a row for {staffId, date}. Gson omits null fields, so the
    // request body simply carries no attendanceId in that case.
    val attendanceId: String? = null,
    val date: String,
    val type: String,
    val remark: String? = null,
    val correctedPunchIn: String? = null,
    val correctedPunchOut: String? = null,
    val correctionReason: String? = null,
)

data class AttendanceRequestResponse(
    val success: Boolean = false,
    val requestId: String? = null,
    val error: String? = null,
)
data class AttendanceRecord(
    // Convex document id of the staffAttendance row — required to raise a
    // correction/remark request against it (/api/hr/attendance/request).
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val date: String?,
    val status: String?,
    val totalMinutes: Int?,
    val approvedAttendance: String?,
    val punchInTime: String? = null,
    val punchOutTime: String? = null,
    val hasOpenSession: Boolean? = null,
    val sessions: List<SessionData>? = emptyList(),
    // Decision metadata mirrored from leaves — populated on
    // approved/rejected rows so the history row can show
    // "By <approver>" with photo and "Approved/Rejected at <date>".
    val approverName: String? = null,
    val approverPhotoUrl: String? = null,
    /** ISO timestamp — approvedAt / reviewedAt / fallback updatedAt. */
    val decidedAt: String? = null,
    // Raw approval label used by system penalty rows. This is distinct from
    // approverName, which is the human-friendly decision metadata.
    val approvedByName: String? = null,
    // System-enforced absence metadata. These fields are returned for task,
    // reporting-officer deadline, and HR deadline penalties so mobile can
    // distinguish them from an ordinary absence (the web shows the same
    // "Absent · Penalty" badge and reason).
    val penaltyKind: String? = null,
    val penaltyReason: String? = null,
    val penaltyTaskId: String? = null,
    val penaltyTaskUrl: String? = null,
    // Fines / Late info.
    // lateFineDeduction is the server-computed fine (₹) returned by
    // /api/hr/attendance/my (staffAttendance.getMyAttendance). fineAmount
    // is only present on some seeded rows; prefer lateFineDeduction.
    val lateMinutes: Int? = null,
    val earlyOutMinutes: Int? = null,
    val earlyMinutes: Int? = null,
    val earlyOut: Int? = null,
    val fineAmount: Double? = null,
    val lateFineDeduction: Double? = null,
    // HR-logged "other fines" — manual deductions for loss of property,
    // indiscipline, etc. Server attributes each fine to its createdAt
    // date so it lands on the right attendance card. Mobile renders one
    // blue banner row per entry under the late-fine banner.
    val otherFines: List<OtherFineData>? = null,
)

data class OtherFineData(
    val typeName: String? = null,
    val amount: Double? = null,
    val notes: String? = null,
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
    // Source of the punch-out specifically (mobile clock-out on a biometric
    // punch-in, etc.). Falls back to `source` when absent.
    val punchOutSource: String? = null,
    val totalMinutes: Int? = null,
    // Where each punch happened: a reverse-geocoded address for mobile punches,
    // or the biometric device / machine name (e.g. "NUTECH 2ND IN") for gate
    // punches — mirrors the web punch log.
    val punchInAddress: String? = null,
    val punchOutAddress: String? = null,
    // Storage IDs of the captured punch photos (mobile clock in/out).
    val punchInPhoto: String? = null,
    val punchOutPhoto: String? = null,
)
data class TodayShiftResponse(
    val success: Boolean? = null,
    val staffId: String? = null,
    val date: String? = null,
    val isWeekoff: Boolean? = null,
    val shift: TodayShift? = null,
)
data class TodayShift(
    val name: String? = null,
    val code: String? = null,
    val schedule: TodayShiftSchedule? = null,
    val graceMinutes: Int? = null,
    val fullDayThresholdMinutes: Int? = null,
    val halfDayThresholdMinutes: Int? = null,
    val isActive: Boolean? = null,
)
data class TodayShiftSchedule(
    val monday: TodayShiftDay? = null,
    val tuesday: TodayShiftDay? = null,
    val wednesday: TodayShiftDay? = null,
    val thursday: TodayShiftDay? = null,
    val friday: TodayShiftDay? = null,
    val saturday: TodayShiftDay? = null,
    val sunday: TodayShiftDay? = null,
)
data class TodayShiftDay(
    val isWorkDay: Boolean? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val breakMinutes: Int? = null,
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

// Attendance approval queue — manager pending-approvals payload.
// Backend returns the records key (not "attendance"), see http.ts:2724.
data class AttendanceApprovalsResponse(
    val success: Boolean,
    val total: Int? = null,
    val records: List<AttendanceApprovalRecord> = emptyList(),
    // Pending correction/remark requests WAITING ON the reporting officer
    // (returned when the pending-approvals call passes requests=true).
    // Separate from records: their _id is an attendanceRequests id and must
    // be approved/rejected with isRequest = true.
    // NULLABLE, not defaulted: Gson skips Kotlin constructor defaults, so a
    // backend that doesn't return the field yet yields null — a non-null
    // declaration would NPE at first use.
    val requests: List<AttendanceApprovalRecord>? = null,
)

data class AttendanceApprovalRecord(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val staffId: String? = null,
    val staffName: String? = null,
    val date: String? = null,
    val punchInTime: String? = null,
    val punchOutTime: String? = null,
    val totalMinutes: Int? = null,
    val source: String? = null,
    val status: String? = null,
    // Backend categorisation chosen by the approver — present | half-day | absent | weekoff | holiday.
    val approvedAttendance: String? = null,
    val approvedByName: String? = null,
    val department: String? = null,
    val designation: String? = null,
    val employeeId: String? = null,
    // Resolved profile-photo URL for the staff member (null when they have no
    // photo). Used to show the avatar on Team/All attendance cards.
    val staffPhotoUrl: String? = null,
    // "remarks" = employee-submitted correction/leave request; "attendance" =
    // normal punch record. Drives the HR Review Attendance/Request sub-tabs.
    val requestType: String? = null,
    // Correction-request payload (Requests sub-tab rows): the times the
    // employee ASKED to be corrected to, plus their stated reason. The
    // punchInTime/punchOutTime above stay the recorded (actual) punches.
    val requestedPunchIn: String? = null,
    val requestedPunchOut: String? = null,
    val requestReason: String? = null,
    // "ro" = waiting on the reporting/attendance officer, "hr" = waiting on
    // HR. Only present on TRUE request rows (attendanceRequests ids) from
    // hr-review / the pending-approvals requests array — attendance rows
    // leave it null, which is how the two are told apart safely.
    val requestStage: String? = null,
    // Server-computed per-DAY late fine (₹) for this record, from
    // staffAttendance.listForReport / enrichReportRecords. Drives the All-tab
    // attendance-fine banner (0 / absent = no fine that day).
    val lateFineDeduction: Double? = null,
    val fineAmount: Double? = null,
    val lateMinutes: Int? = null,
    val penaltyKind: String? = null,
    val penaltyReason: String? = null,
    val penaltyTaskId: String? = null,
    val penaltyTaskUrl: String? = null,
)

// Body for /api/hr/attendance/approve. Backend defaults the attendance bucket
// to "present" when omitted; the app always sends an explicit choice to make
// audit logs unambiguous.
data class ApproveAttendanceRequest(
    val id: String,
    val approvedAttendance: String,
    // True when `id` is an attendanceRequests doc (HR Review → Requests row);
    // the backend then applies the request-review mutation instead of the
    // plain attendance approve. Omitted (null) for normal punch records.
    val isRequest: Boolean? = null,
)

// Punch models
data class PunchRequest(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val photo: String? = null,
    val deviceId: String? = null,
    val source: String = "mobile",
    val remarks: String? = null,
    // ISO-8601 device time captured at the moment the staff tapped punch. Lets
    // a slow/offline-then-synced punch record the real tap time server-side.
    val clientPunchTime: String? = null
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

/** GET /api/hr/staff/digital-sign — the caller's saved signature, if any. */
data class DigitalSignResponse(
    val success: Boolean = false,
    val hasSignature: Boolean = false,
    val storageId: String? = null,
    val url: String? = null,
    val fileName: String? = null,
    val error: String? = null,
)

/** POST /api/hr/staff/digital-sign — persist a freshly-drawn signature. */
data class DigitalSignRequest(val storageId: String, val fileName: String? = null)
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val leaveId: String? = null,
    // Applicant's staff id — lets the approval UI recognise the viewer's OWN
    // leave and hide its Approve/Reject (no self-approval).
    val staffId: String? = null,
    val staffName: String? = null,
    val leaveType: String?,
    val fromDate: String?,
    val toDate: String?,
    val reason: String?,
    val status: String?,
    @SerializedName("_creationTime") val createdAt: Double?,
    // Decision metadata — populated server-side on /my for approved
    // and rejected rows so the mobile card can render "By <approver>"
    // with the person's avatar and "Approved/Rejected at <date>".
    val approverName: String? = null,
    val approverPhotoUrl: String? = null,
    /** ISO datetime — approvedOn / rejectedOn / fallback updatedAt. */
    val decidedAt: String? = null,
    val isHalfDay: Boolean? = null,
    val halfDaySession: String? = null
)
data class ApplyLeaveRequest(
    val leaveType: String,
    val fromDate: String,
    val toDate: String,
    val reason: String,
    val reportingToId: String? = null,
    val reportingToName: String? = null,
    val isHalfDay: Boolean? = null,
    val halfDaySession: String? = null,
    val halfDayType: String? = null
)
data class ApplyLeaveResponse(val success: Boolean, val leaveId: String?, val error: String? = null)

// Comp Off models — an earned credit is valid only within its earned month
// and is spent on a single day to create a "compensatory" leave.
data class CompOffCredit(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val earnedDate: String? = null,
    val expiresAt: String? = null,
    val source: String? = null,
    val status: String? = null
)
data class CompOffCreditsResponse(
    val success: Boolean = false,
    val credits: List<CompOffCredit> = emptyList(),
    val error: String? = null
)
data class ApplyCompOffRequest(val creditId: String, val date: String)
data class ApplyCompOffResponse(val success: Boolean, val leaveId: String? = null, val error: String? = null)

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
    // Nullable so "field absent" (older backend) is distinguishable from an
    // explicit 0-day allocation — HR policy honors 0 as "category disabled".
    val casualPerYear: Int? = null,
    val sickPerYear: Int? = null,
    val earnedPerYear: Int? = null,
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val permissionId: String? = null,
    // Authoritative owner of the row. The mobile filters its "My
    // Permissions" view against this so a misbehaving backend can't
    // leak other staff's slips into the user's list.
    val staffId: String? = null,
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
    val reason: String,
    val reportingToId: String? = null,
    val reportingToName: String? = null
)
data class ApplyPermissionResponse(val success: Boolean, val permissionId: String?, val error: String? = null)

// Common
// ── Profile photo ──
data class SetProfilePhotoRequest(val storageId: String)
data class SetProfilePhotoResponse(
    val success: Boolean = false,
    val staff: StaffFullData? = null,
    val photo: ProfilePhotoData? = null,
    val error: String? = null
)
data class ProfilePhotoData(val storageId: String? = null, val url: String? = null)

// ── Loans models ──
// All numeric loan fields use Double to match Convex's v.number() (Float64).
// Gson throws if a JSON number with any decimal/exponent lands in a Long?
// field — which is what happens when the backend returns amounts like
// 25000.0 from a number column.
data class LoanData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val loanId: String?,
    val staffId: String?,
    val staffName: String?,
    val employeeId: String?,
    val principalAmount: Double? = null,
    val loanAmount: Double? = null,
    val annualInterestRate: Double? = null,
    val interestType: String? = null,
    val disbursedDate: String? = null,
    val repaymentStartMonth: String? = null,
    val repaymentEndMonth: String? = null,
    val monthlyDeduction: Double? = null,
    val totalRepaid: Double? = null,
    val remainingBalance: Double? = null,
    val status: String? = null,
    val purpose: String? = null,
    val notes: String? = null,
    val approvalStatus: String? = null,
    // "loan" | "salary_advance" — the authoritative type flag from the
    // backend (drives the Loans vs Advance split, not interestType/purpose).
    val requestType: String? = null,
    val currentStage: String? = null,
    val nominee1Status: String? = null,
    val nominee2Status: String? = null,
    val repayments: List<LoanRepaymentData>? = null,
    // Nominee fields for approval chain
    val nominee1Id: String? = null,
    val nominee1Name: String? = null,
    // Backend stores the nominee e-signature under *SignatureStorageId; map it
    // so the signature preview actually has an id to load.
    @SerializedName("nominee1SignatureStorageId")
    val nominee1ESignature: String? = null,
    val nominee2Id: String? = null,
    val nominee2Name: String? = null,
    @SerializedName("nominee2SignatureStorageId")
    val nominee2ESignature: String? = null,
    // Resolved approval-chain approvers (who's next at each role). assigned*
    // are stamped at submit (the reporting/dept GM & AVP); gm/avp/accountantName
    // are filled as each stage acts.
    val assignedGmName: String? = null,
    val assignedAvpName: String? = null,
    val gmName: String? = null,
    val avpName: String? = null,
    val hrApprovalName: String? = null,
    val accountantName: String? = null,
    // Display-only names the backend resolves for stages that haven't acted yet
    // (who *will* approve at GM/AVP/HR/Accounts), so the tracker can label every
    // step even while pending.
    val resolvedGmName: String? = null,
    val resolvedAvpName: String? = null,
    val resolvedHrName: String? = null,
    val resolvedAccountantName: String? = null
)

data class LoanRepaymentData(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val loanId: String? = null,
    val staffId: String? = null,
    val month: String? = null,
    val amount: Double? = null,
    val mode: String? = null,
    val notes: String? = null,
    val createdAt: String? = null
)

// ── Configured Approval Workflow (loans/advances) ──
// Shape mirrors convex workflows.getRunByEntity → getWorkflowRunDetails:
// { run, steps }. Used to render the real, configured approval chain on the
// loan/advance pending tracker instead of the hardcoded nominee/GM/AVP/HR
// dots. `workflow` is null for legacy code-stage rows.
data class LoanWorkflowResponse(
    val success: Boolean = false,
    val workflow: WorkflowRunDetails? = null,
)

data class WorkflowRunDetails(
    val run: WorkflowRunData? = null,
    val steps: List<WorkflowStepData>? = null,
)

data class WorkflowRunData(
    // pending | approved | rejected | cancelled
    val status: String? = null,
    val currentStepOrder: Int? = null,
    val currentAssigneeStaffId: String? = null,
    val currentAssigneeName: String? = null,
    val currentAssigneeRole: String? = null,
)

data class WorkflowStepData(
    val stepOrder: Int? = null,
    val name: String? = null,
    val approverType: String? = null,
    val approverRole: String? = null,
    val approverDesignation: String? = null,
    val resolvedStaffName: String? = null,
    // pending | approved | rejected | skipped
    val status: String? = null,
    val actedByName: String? = null,
    val actedAt: String? = null,
)

data class LoansSummary(
    val totalLoans: Int = 0,
    val activeCount: Int = 0,
    val previousCount: Int = 0,
    val pendingCount: Int = 0,
    val totalDisbursed: Double = 0.0,
    val totalRepaid: Double = 0.0,
    val currentOutstanding: Double = 0.0
)

data class MyLoansResponse(
    val success: Boolean = false,
    val summary: LoansSummary? = null,
    val active: List<LoanData> = emptyList(),
    val previous: List<LoanData> = emptyList(),
    val pending: List<LoanData> = emptyList(),
    val error: String? = null
)

data class LoanDetailResponse(
    val success: Boolean = false,
    val loan: LoanData? = null,
    val error: String? = null
)

data class LoanRepaymentsResponse(
    val success: Boolean = false,
    val total: Int = 0,
    val repayments: List<LoanRepaymentData> = emptyList(),
    val error: String? = null
)

data class ApplyLoanRequest(
    val nominee1Id: String? = null,
    val nominee1Name: String? = null,
    val nominee2Id: String? = null,
    val nominee2Name: String? = null,
    val loanAmount: Double? = null,
    val interestType: String? = null,
    val disbursedDate: String? = null,
    val repaymentStartMonth: String? = null,
    val tenureMonths: Int? = null,
    val originalDocument: String? = null,
    val purpose: String? = null,
    val notes: String? = null
)

data class ApplyLoanResponse(
    val success: Boolean = false,
    val loanId: String? = null,
    val error: String? = null
)

data class ApproveLoanRequest(
    val id: String,
    val eSignatureId: String? = null
)

data class IdRequest(val id: String)
data class RejectRequest(val id: String, val reason: String, val isRequest: Boolean? = null)
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
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
data class StaffCounts(
    val all: Int = 0,
    val active: Int = 0,
    val inactive: Int = 0,
)

data class StaffCountResponse(
    val success: Boolean,
    // Current backend nests the breakdown under `counts` and puts the grand
    // total at top level; older shape had the counts flat. Support both.
    val counts: StaffCounts? = null,
    val total: Int = 0,
    val all: Int = 0,
    val active: Int = 0,
    val inactive: Int = 0,
) {
    val activeCount: Int get() = counts?.active ?: active
    val inactiveCount: Int get() = counts?.inactive ?: inactive
    val allCount: Int get() = counts?.all ?: total.takeIf { it > 0 } ?: all
}

// Staff detail
data class StaffDetailResponse(val success: Boolean, val staff: StaffFullData?)
data class StaffFullData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
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
    val reportingTo: String? = null,
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val name: String?, val description: String?,
    val type: String?, val memberCount: Int?,
    val unreadCount: Int?, val slug: String?,
    val muted: Boolean?,
    val lastMessageAt: Long? = null,
    val lastMessagePreview: String? = null,
    val mentionCount: Int? = null,
    // Group avatar (resolved storage URL) + creator — WhatsApp-style groups.
    val avatarUrl: String? = null,
    val createdBy: String? = null,
)
data class ChannelDetailResponse(val success: Boolean, val channel: ChannelData?)
data class CreateChannelRequest(
    val name: String,
    val description: String? = null,
    val type: String,
    val memberIds: List<String>? = null,
    val avatarStorageId: String? = null,
)
data class CreateChannelResponse(val success: Boolean, val channelId: String?)
data class ConversationsResponse(val success: Boolean, val conversations: List<ConversationData> = emptyList())
data class ConversationDetailResponse(val success: Boolean, val conversation: ConversationData?)
data class ConversationData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val displayName: String?, val type: String?,
    val unreadCount: Int?, val muted: Boolean?,
    val lastMessage: MessageData?,
    val lastMessageAt: Long?,
    val lastMessagePreview: String? = null,
    val lastMessageSenderId: String? = null,
    // Group-dm parity: creator is always an admin; description is editable
    // by admins from the group info screen.
    val createdBy: String? = null,
    val description: String? = null,
    val participants: List<ParticipantData>?
)
data class ParticipantData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val name: String?,
    val photo: String? = null,
    // "admin" | "member" (absent = member; creator implicitly admin).
    val role: String? = null,
)
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    @SerializedName("_creationTime") val creationTime: Double?,
    val body: String?, val senderId: String?, val senderName: String?,
    val channelId: String?, val conversationId: String?,
    val isDeleted: Boolean?, val isEdited: Boolean?,
    val replyCount: Int?, val parentMessageId: String?,
    // "system" for server-generated group events ("X added Y", "X left") —
    // rendered as a centered pill, not a bubble.
    val kind: String? = null,
    val attachments: List<MessageAttachmentData>? = null,
    val reactions: List<ReactionData>? = null,
    // Local-only optimistic-send fields. Never serialised back to the
    // server (Gson ignores Transient + nulls). When set, the UI marks
    // the bubble as pending (clock icon) and the ChatPendingQueue is
    // the source of truth for retrying delivery.
    @Transient val localPendingId: String? = null,
    @Transient val hasFailed: Boolean = false,
)
data class MessageAttachmentData(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val staffId: String?,
    val staffName: String?,
    val expiresAt: Long?
)

// ── Chat search + attachments ──────────────────────────────────────────────

data class ChatSearchMessage(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
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
    @SerializedName("_id", alternate = ["id"]) val id: String?,
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

// ── Chat channel management models ──────────────────────────────────────────

data class ChannelMembersResponse(val success: Boolean, val members: List<ChannelMemberData> = emptyList())
data class ChannelMemberData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val staffId: String?,
    val role: String?,
    val muted: Boolean? = null,
    val staffName: String? = null,
    val staffRole: String? = null,
    val staffDesignation: String? = null,
    val profilePhoto: String? = null
)

data class UpdateChannelRequest(
    val channelId: String,
    val name: String? = null,
    val description: String? = null,
    val type: String? = null,
    val avatarStorageId: String? = null,
    val avatarUrl: String? = null,
    val avatar: String? = null,
    val profilePhoto: String? = null,
    val photo: String? = null,
    val image: String? = null,
    val imageUrl: String? = null,
    val photoUrl: String? = null,
    val avatarId: String? = null,
)

data class ChannelMemberRequest(
    val channelId: String,
    val staffId: String
)

data class SetMuteRequest(
    val channelId: String? = null,
    val conversationId: String? = null,
    val muted: Boolean
)

data class SetChannelRoleRequest(
    val channelId: String,
    val targetStaffId: String,
    val role: String
)

// ── Chat conversation management models ──────────────────────────────────────

data class ConversationMemberRequest(
    val conversationId: String,
    val staffId: String
)

data class UpdateGroupConversationRequest(
    val conversationId: String,
    val name: String? = null,
    val description: String? = null,
)

data class SetConversationRoleRequest(
    val conversationId: String,
    val targetStaffId: String,
    val role: String,
)

// ── Chat message edit/delete/unread models ──────────────────────────────────

data class SingleMessageResponse(val success: Boolean, val message: MessageData? = null)

data class UnreadSummaryResponse(
    val success: Boolean,
    val channels: Int = 0,
    val dms: Int = 0,
    val mentions: Int = 0,
    val total: Int = 0
)

data class EditMessageRequest(
    val messageId: String,
    val body: String
)

data class DeleteMessageRequest(
    val messageId: String
)

// ── Chat reaction models ────────────────────────────────────────────────────

data class ReactionsResponse(val success: Boolean, val reactions: List<ReactionData> = emptyList())
data class ReactionData(
    val emoji: String?,
    val count: Int? = 0,
    val staffIds: List<String>? = null,
    val mine: Boolean? = false
)

data class ReactionRequest(
    val messageId: String,
    val emoji: String
)

// ── Chat presence models ────────────────────────────────────────────────────

data class PresenceData(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val staffId: String? = null,
    val status: String? = null,
    val lastSeenAt: Long? = null
)

data class PresenceResponse(val success: Boolean, val presence: PresenceData? = null)
data class PresenceListResponse(val success: Boolean, val online: List<PresenceData> = emptyList())
data class PresenceHeartbeatRequest(val status: String? = null)

// ── Staff self-edit (personal + family fields) ─────────────────────────────

data class UpdateMyProfileRequest(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
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
    @SerializedName("_id", alternate = ["id"]) val id: String,
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
    // Estimated / actual cost in INR. `tasks.create` stores both as
    // optional numbers on the schema; the Task Overview surfaces
    // estimatedCost in the Est. Cost card.
    val estimatedCost: Double? = null,
    val actualCost: Double? = null,
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

// ── Task Manager (daily tasks) ──────────────────────────────────────────────
// Shape mirrors the web `dailyTasks.listForTaskManager` enriched task; only
// the fields the mobile Task Manager row needs are declared here.
data class DailyTaskData(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val title: String? = null,
    val taskName: String? = null,
    val label: String? = null,
    val priority: String? = null,
    val description: String? = null,
    val deadline: String? = null,
    // Staff ids — needed to compute the "My Tasks" / "My Team Tasks" /
    // "Assigned By Me" info cards client-side, exactly like the web page.
    val assignedTo: String? = null,
    val assignedBy: String? = null,
    val assignedToName: String? = null,
    val assignedByName: String? = null,
    val assignedByRole: String? = null,
    val assignedByDesignation: String? = null,
    val creatorRole: String? = null,
    val creatorDesignation: String? = null,
    val taskCategory: String? = null,
    val status: String? = null,
    // Module label ("HR", "Marketing", "Land Procurement", …) resolved by the
    // backend so the mobile module tabs match the web without re-deriving it.
    val module: String? = null,
    // Whether a deadline-extension request is pending on this task (Extension
    // Requests card).
    // Older backends sent a Boolean; the extension-requests feature now
    // sends the full request OBJECT (or null). Typed Any? so one task with
    // an object can't fail the whole list parse — treat "non-null and not
    // false" as "has a pending extension request".
    val pendingExtensionRequest: Any? = null,
    val sourceReferenceType: String? = null,
    val sourceReferenceId: String? = null,
    val actionUrl: String? = null,
    @SerializedName("_creationTime") val creationTime: Double? = null
)

data class TaskManagerResponse(
    val success: Boolean,
    val tasks: List<DailyTaskData> = emptyList(),
    val teamIds: List<String> = emptyList(),
    val scope: String? = null,
    val error: String? = null
)

data class TeamScopeResponse(
    val success: Boolean = false,
    val teamCount: Int = 0,
    val hasTeam: Boolean = false,
    val error: String? = null
)

data class UpdateDailyTaskStatusRequest(
    val id: String,
    val status: String
)

data class DailyTaskStatusResponse(
    val success: Boolean,
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
    val progressSnapshot: Int? = null,
    val images: List<TaskUpdateImage>? = null
)

data class TaskUpdateImage(
    val storageId: String,
    val url: String? = null,
    val name: String? = null
)

data class TaskMutationResponse(
    val success: Boolean,
    val updateId: String? = null,
    val task: TaskData? = null,
    val error: String? = null
)

// ── Project Management: projects list / detail / per-project tasks ─────────

data class ProjectSummary(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val name: String? = null,
    val description: String? = null,
    val status: String? = null,
    val progress: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val budget: Double? = null,
    val location: String? = null,
    val managerName: String? = null,
    val staffManagerId: String? = null,
    val projectType: String? = null,
    // Raw project doc rides through /api/projects/get on every deploy, so
    // this is the prod-safe source for the Special payment plan gate.
    val specialPaymentEnabled: Boolean? = null,
)

data class MyProjectsResponse(
    val success: Boolean,
    val total: Int? = null,
    val projects: List<ProjectSummary> = emptyList(),
    val error: String? = null
)

data class ProjectDetailResponse(
    val success: Boolean,
    val project: ProjectSummary? = null,
    val isProjectManager: Boolean? = null,
    val membershipRole: String? = null,
    val error: String? = null
)

data class ProjectTasksResponse(
    val success: Boolean,
    val total: Int? = null,
    val tasks: List<TaskData> = emptyList(),
    val error: String? = null
)

data class TaskTimelineEntry(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val taskId: String? = null,
    val projectId: String? = null,
    val date: String? = null,
    val todaysUpdate: String? = null,
    val blocker: String? = null,
    val tomorrowsPlan: String? = null,
    val progressSnapshot: Int? = null,
    val images: List<TaskUpdateImage>? = null,
    val createdBy: String? = null,
    // Server stores createdAt as an ISO-8601 string (`new Date().toISOString()`
    // in taskUpdates.create). Declaring this as Double crashed Gson with
    // `NumberFormatException: For input string: "2026-05-23T20:04:07.161Z"`
    // when the Time Line sheet tried to load.
    val createdAt: String? = null,
    @SerializedName("_creationTime") val creationTime: Double? = null
)

data class TaskTimelineResponse(
    val success: Boolean,
    val total: Int? = null,
    val updates: List<TaskTimelineEntry> = emptyList(),
    val error: String? = null
)

data class TaskResourceEntry(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val taskId: String? = null,
    val resourceType: String? = null,    // material | labour | equipment
    val itemName: String? = null,
    val unit: String? = null,
    val budgetQty: Double? = null,
    val plannedQty: Double? = null,
    val actualQty: Double? = null,
    val rate: Double? = null,
    val isLumpsum: Boolean? = null,
    val lumpsumAmount: Double? = null
)

data class TaskResourcesResponse(
    val success: Boolean,
    val total: Int? = null,
    val resources: List<TaskResourceEntry> = emptyList(),
    val error: String? = null
)

// ── Storage (Convex upload URL flow) ───────────────────────────────────────

data class StorageUploadUrlResponse(
    val success: Boolean,
    val uploadUrl: String? = null,
    val error: String? = null
)

data class StorageUploadResultResponse(
    val storageId: String? = null
)

// ── Project Expenses ───────────────────────────────────────────────────────

data class ExpenseReceipt(
    val storageId: String,
    val url: String? = null,
    val name: String? = null
)

data class ProjectExpense(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val projectId: String? = null,
    val category: String,                // labour | materials | equipment | other
    val amount: Double,
    val date: String,                    // YYYY-MM-DD
    val paymentMethod: String? = null,
    val notes: String? = null,
    val receipts: List<ExpenseReceipt>? = null,
    val paid: Boolean = false,
    val paidAt: Double? = null,
    val paidByStaffId: String? = null,
    val createdByStaffId: String? = null,
    val createdAt: Double? = null,
    val updatedAt: Double? = null,
    @SerializedName("_creationTime") val creationTime: Double? = null
)

data class ExpenseCategoryTotals(
    val labour: Double = 0.0,
    val materials: Double = 0.0,
    val equipment: Double = 0.0,
    val other: Double = 0.0
)

data class ExpenseTotals(
    val byCategory: ExpenseCategoryTotals = ExpenseCategoryTotals(),
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val pending: Double = 0.0,
    val count: Int = 0
)

data class ProjectExpensesResponse(
    val success: Boolean,
    val total: Int? = null,
    val expenses: List<ProjectExpense> = emptyList(),
    val totals: ExpenseTotals? = null,
    val error: String? = null
)

data class ProjectExpenseDetailResponse(
    val success: Boolean,
    val expense: ProjectExpenseDetail? = null,
    val error: String? = null
)

data class ProjectExpenseDetail(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val projectId: String? = null,
    val category: String,
    val amount: Double,
    val date: String,
    val paymentMethod: String? = null,
    val notes: String? = null,
    val receipts: List<ExpenseReceipt>? = null,
    val paid: Boolean = false,
    val paidAt: Double? = null,
    val paidByStaffId: String? = null,
    val createdByStaffId: String? = null,
    val createdAt: Double? = null,
    val updatedAt: Double? = null,
    val project: ProjectSummary? = null,
    val createdByName: String? = null,
    val paidByName: String? = null
)

data class CreateProjectExpenseRequest(
    val projectId: String,
    val category: String,
    val amount: Double,
    val date: String,
    val paymentMethod: String? = null,
    val notes: String? = null,
    val receipts: List<ExpenseReceipt>? = null,
    val paid: Boolean = false
)

data class ProjectExpenseCreateResponse(
    val success: Boolean,
    val id: String? = null,
    val error: String? = null
)

data class UpdateProjectExpenseRequest(
    val id: String,
    val category: String? = null,
    val amount: Double? = null,
    val date: String? = null,
    val paymentMethod: String? = null,
    val notes: String? = null,
    val receipts: List<ExpenseReceipt>? = null
)

data class MarkExpensePaidRequest(
    val id: String,
    val paid: Boolean
)

// ── Telecaller leads (mobile) ──────────────────────────────────────────────

data class TelecallerLeadData(
    @SerializedName("_id", alternate = ["id"]) val id: String,
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

data class ClientSearchResponse(
    val success: Boolean,
    val client: ClientProfile? = null,
    val error: String? = null,
)

data class ClientProfile(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val title: String? = null,
    val clientName: String? = null,
    val clientImageStorageId: String? = null,
    val clientImageFileName: String? = null,
    val fatherSpouseName: String? = null,
    val dateOfBirth: String? = null,
    val anniversaryDate: String? = null,
    val nationality: String? = null,
    val mobileNumber: String? = null,
    val alternateNumbers: String? = null,
    val whatsappNumber: String? = null,
    val email: String? = null,
    val doorNo: String? = null,
    val streetName: String? = null,
    val homeAddress: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val landmark: String? = null,
    val formattedAddress: String? = null,
    val pincode: String? = null,
    val state: String? = null,
    val district: String? = null,
    val location: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val googleMapsLink: String? = null,
    val profession: String? = null,
    val designation: String? = null,
    val department: String? = null,
    val incomePerAnnum: String? = null,
    val officeName: String? = null,
    val officeAddress: String? = null,
    val officeDoorNo: String? = null,
    val officeStreet: String? = null,
    val officeAddressLine1: String? = null,
    val officeAddressLine2: String? = null,
    val officeArea: String? = null,
    val officePincode: String? = null,
    val officeMobile: String? = null,
    val officePhone: String? = null,
    val officeEmail: String? = null,
    val aadhaar: String? = null,
    val pan: String? = null,
    val referenceName1: String? = null,
    val referenceMobile1: String? = null,
    val referenceProfession1: String? = null,
    val referenceName2: String? = null,
    val referenceMobile2: String? = null,
    val referenceProfession2: String? = null,
)

data class BookingConversionPrefillResponse(
    val success: Boolean = false,
    val prefill: BookingConversionPrefill? = null,
    val error: String? = null,
)

data class BookingConversionPrefill(
    val bookingId: String? = null,
    val bookingRefNo: String? = null,
    val previousProject: String? = null,
    val previousPlot: String? = null,
    val totalAmountPaid: Double? = null,
)

data class BookingExchangeSourcesResponse(
    val success: Boolean = false,
    val bookings: List<BookingExchangeSource>? = null,
    val error: String? = null,
)

data class BookingExchangeSource(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val bookingRefNo: String? = null,
    val clientName: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val plotId: String? = null,
    val plotNo: String? = null,
    val extentSqft: Double? = null,
    val exchangeValue: Double? = null,
    val bookingDate: String? = null,
)

/**
 * Body for /api/telecaller/leads/update — used by the outcome
 * sheet's Edit-mode submit to push field-staff edits back to the
 * lead. Every field is optional; only ones the user actually
 * changed are sent. manualProfile.* mirrors the schema shape so
 * a single PATCH covers the whole client-form payload.
 */
data class UpdateTelecallerLeadRequest(
    @SerializedName("id") val leadId: String,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val emailId: String? = null,
    val alternateNumber: String? = null,
    val clientCity: String? = null,
    val locationPreferred: String? = null,
    val manualProfile: ManualProfilePatch? = null,
)

data class ManualProfilePatch(
    val clientName: String? = null,
    val pincode: String? = null,
    val address: String? = null,
    val state: String? = null,
    val district: String? = null,
    val alternateMobileNumber: String? = null,
    // Inbound-only fields — server stores them on telecallerLeads
    // .manualProfile but they're not yet wired into the Edit-mode
    // outcome-sheet POST, so they stay null on writes. Adding them
    // lets the CP create form autofill door / landmark out of an
    // operator-edited profile when available.
    val doorNo: String? = null,
    val landmark: String? = null,
)

data class TelecallerLeadSearchData(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val emailId: String? = null,
    val clientCity: String? = null,
    val locationPreferred: String? = null,
    val suggestedVisitAddress: String? = null,
    val latestAnalysisProfile: LeadAnalysisProfile? = null,
    // Operator-edited manual profile (from the web Edit Live Profile
    // dialog). When present, prefer these values over the AI-derived
    // latestAnalysisProfile — they reflect explicit corrections the
    // operator typed, not the AI's best guess at parsing the call.
    val manualProfile: ManualProfilePatch? = null,
    // Snapshot of the saved clientPlaces row tied to this lead. Set
    // after a prior CP visit was created — carries the most reliable
    // door/pincode/address since it was confirmed by an actual visit.
    // The CP create form prefers this over both AI analysis and
    // manual edits.
    val clientPlaceProfile: LeadClientPlaceProfile? = null,
    // Best free-text/coord hints surfaced alongside the structured
    // profiles. Used to pre-populate the lat/lng + maps-link cells
    // on the CP create form when the lead already has them.
    val suggestedVisitLat: Double? = null,
    val suggestedVisitLng: Double? = null,
    val suggestedGoogleMapsLink: String? = null,
    val projectId: String? = null,
    val assignedToStaffId: String? = null,
    val assignedToStaffName: String? = null,
)

/** Inbound-only — corresponds to clientPlaces row enrichment from
 *  telecallerLeads.searchByPhone. doorNo / pincode / city / state /
 *  landmark are surfaced for the CP create form's autofill. */
data class LeadClientPlaceProfile(
    val doorNo: String? = null,
    val pincode: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val district: String? = null,
    val landmark: String? = null,
    val formattedAddress: String? = null,
)

data class LeadAnalysisProfile(
    val clientName: String? = null,
    val pincode: String? = null,
    val address: String? = null,
    val landmark: String? = null,
    val state: String? = null,
    val district: String? = null,
    // Server-side telecallerLeads.searchByPhone enriches the AI
    // analysis with doorNo when the analysis itself extracted one.
    val doorNo: String? = null,
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

data class MobileDialerConfigResponse(
    val success: Boolean,
    val configured: Boolean = false,
    val provider: String? = null,
    val mode: String? = null,
    val apiUrl: String? = null,
    val staff: MobileDialerStaff? = null,
    val mapping: MobileDialerMapping? = null,
    val features: MobileDialerFeatures? = null,
    val error: String? = null,
)

data class MobileDialerStaff(
    val id: String? = null,
    val name: String? = null,
    val employeeId: String? = null,
)

data class MobileDialerMapping(
    val staffName: String? = null,
    val extension: String? = null,
    val token: String? = null,
    val tokenExpiresAt: String? = null,
    val active: Boolean = false,
)

data class MobileDialerFeatures(
    val outbound: Boolean = false,
    val incomingPush: Boolean = false,
    val pickup: Boolean = false,
    val hangup: Boolean = false,
    val mute: Boolean = false,
    val hold: Boolean = false,
    val pushProvider: String? = null,
    val pushConfigSource: String? = null,
)

// ── Marketing: Projects + Inventory Units (KOS-52) ──────────────────────────
data class MarketingProject(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val name: String? = null,
    val scope: String? = null,
    val status: String? = null,
    val location: String? = null,
    // Gates the "Special (max 180 days)" payment plan in the booking form,
    // mirroring the web. Null until the backend exposing it is deployed.
    val specialPaymentEnabled: Boolean? = null,
    val minimumAdvanceAmount: Double? = null,
    val allotmentDueDays: Double? = null,
    val promoOffer: String? = null,
    val projectOfferValue: Double? = null,
    val projectOfferTerms: String? = null,
    val projectOfferValidityDays: Double? = null,
)

data class MarketingProjectsResponse(
    val success: Boolean,
    val projects: List<MarketingProject> = emptyList(),
    val error: String? = null,
)

data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val status: String = "proposed",
    val startDate: String,
    val endDate: String,
    val budget: Double? = null,
)
data class CreateProjectResponse(
    val success: Boolean = false,
    val project: MarketingProject? = null,
    val error: String? = null,
)

data class MaterialCatalogItem(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val name: String? = null,
    val unit: String? = null,
    val category: String? = null,
    val itemCode: String? = null,
    val brand: String? = null,
)

data class MaterialsResponse(
    val success: Boolean = false,
    val total: Int? = null,
    val materials: List<MaterialCatalogItem> = emptyList(),
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
    @SerializedName("_id", alternate = ["id"]) val id: String,
    val projectId: String? = null,
    val unitNumber: String? = null,
    val unitType: String? = null,           // plot|villa|flat
    val facing: String? = null,             // N|E|S|W|NE|NW|SE|SW
    val area: Double? = null,
    val dimensions: String? = null,
    val floor: Int? = null,
    val block: String? = null,
    val priceSnapshot: Double? = null,
    val status: String? = null,                     // available|held|booked|sold
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
    @SerializedName("_id", alternate = ["id"]) val id: String,
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

data class BookingPlotPrefillProject(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val name: String? = null,
    val ratePerSqft: Double? = null,
    val guidelineRatePerSqft: Double? = null,
    val gstPercent: Double? = null,
    val specialPaymentEnabled: Boolean? = null,
    val minimumAdvanceAmount: Double? = null,
    val allotmentDueDays: Double? = null,
    val promoOffer: String? = null,
    val projectOfferValue: Double? = null,
    val projectOfferTerms: String? = null,
    val projectOfferValidityDays: Double? = null,
)

data class BookingPlotPrefillPlot(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val plotNo: String? = null,
    val area: Double? = null,
    val ratePerSqft: Double? = null,
    val plotCost: Double? = null,
    val guidelineValue: Double? = null,
)

data class BookingPlotPrefillFields(
    val bookingCost: Double? = null,
    val agreedAmount: Double? = null,
    val guidelineValue: Double? = null,
    val registrationCharges: Double? = null,
    val gstAmount: Double? = null,
    val documentCharges: Double? = null,
    val pattaCharges: Double? = null,
    val otherCharges: Double? = null,
    val advanceAmount: Double? = null,
    val advanceDueDate: String? = null,
    val allotmentDueAmount: Double? = null,
    val allotmentDueDate: String? = null,
)

data class BookingPaymentSchedulePrefill(
    val description: String? = null,
    val paymentPercent: Double? = null,
    val daysFromBooking: Double? = null,
    val amount: Double? = null,
    val dueDate: String? = null,
)

data class BookingPlotPrefillResponse(
    val success: Boolean,
    val project: BookingPlotPrefillProject? = null,
    val plot: BookingPlotPrefillPlot? = null,
    val fields: BookingPlotPrefillFields? = null,
    val schedules: List<BookingPaymentSchedulePrefill>? = emptyList(),
    val error: String? = null,
)

// ── Marketing: Bookings (KOS-52) ────────────────────────────────────────────
// Mobile sends only the fields the booking picker collects. plotId is always
// included when the user selected an inventory unit — server-side validator
// (KOS-40) rejects mismatched project + sold/booked rows.
data class CreateBookingRequest(
    val clientName: String,
    val mobileNumber: String,
    val bookingDate: String,                // yyyy-MM-dd
    val leadId: String? = null,
    val title: String? = null,
    // Optional client photo (web parity) — stored on the clients master row.
    val clientImageStorageId: String? = null,
    val clientImageFileName: String? = null,
    val fatherSpouseName: String? = null,
    val dateOfBirth: String? = null,
    val anniversaryDate: String? = null,
    val alternateNumbers: String? = null,
    val whatsappNumber: String? = null,
    val email: String? = null,
    val pincode: String? = null,
    val homeAddress: String? = null,
    val profession: String? = null,
    val designation: String? = null,
    val department: String? = null,
    val incomePerAnnum: String? = null,
    val officeName: String? = null,
    val officeAddress: String? = null,
    val officeArea: String? = null,
    val officePincode: String? = null,
    val state: String? = null,
    val district: String? = null,
    val location: String? = null,
    val officeMobile: String? = null,
    val officePhone: String? = null,
    val officeEmail: String? = null,
    val nationality: String? = null,
    val projectId: String? = null,
    val plotId: String? = null,
    val plotNo: String? = null,
    val bookingType: String? = null,
    val conversionManualEntry: Boolean? = null,
    val manualConversionProjectName: String? = null,
    val manualConversionPlotNo: String? = null,
    val manualConversionCredit: Double? = null,
    val conversionNotes: String? = null,
    val sourceExchangeBookingId: String? = null,
    val exchangeManualEntry: Boolean? = null,
    val exchangeLookupProjectId: String? = null,
    val exchangeLookupPlotNo: String? = null,
    val exchangeConnectedMobileNumber: String? = null,
    val manualExchangeProjectName: String? = null,
    val manualExchangePlotNo: String? = null,
    val manualExchangeExtentSqft: Double? = null,
    val exchangeOldRegisteredValue: Double? = null,
    val exchangeNewValue: Double? = null,
    val exchangeBalancePayable: Double? = null,
    val exchangeNotes: String? = null,
    val cefNo: String? = null,
    val isDuplicateBooking: Boolean? = null,
    val isAgainstSV: Boolean? = null,
    val svName: String? = null,
    val svMobileNo: String? = null,
    val propertyType: String? = null,
    val bookingMode: String? = null,
    val clientSource: String? = null,
    val clientSourceName: String? = null,
    val clientSourceMobile: String? = null,
    val referralBenefit: String? = null,
    val bookingCost: Double? = null,
    val guidelineValue: Double? = null,
    val specialConsideration: Double? = null,
    val specialConsiderationReason: String? = null,
    val discountApprovedBy: String? = null,
    val specialConsiderationValidity: Double? = null,
    val promotionalOffers: String? = null,
    val promotionalOffersTnC: String? = null,
    val promotionalOfferValue: Double? = null,
    val offerValidityPeriod: Double? = null,
    val agreedAmount: Double? = null,
    val registrationCharges: Double? = null,
    val gstAmount: Double? = null,
    val gstApplicable: Boolean? = null,
    val documentCharges: Double? = null,
    val pattaCharges: Double? = null,
    val otherCharges: Double? = null,
    val otherChargesApplicable: Boolean? = null,
    val advanceAmount: Double? = null,
    val balanceAmount: Double? = null,
    val paymentMode: String? = null,
    val advanceTransactionId: String? = null,
    val advancePaymentProofStorageId: String? = null,
    val advancePaymentProofFileName: String? = null,
    val advanceInstrumentNo: String? = null,
    val advanceBankName: String? = null,
    val advanceBankBranch: String? = null,
    val advanceInstrumentDate: String? = null,
    // Mirrors the web Booking · Customer Payment Category dropdown.
    // Mobile sends just the letter ("A" / "B" / "C") so the server's
    // bookings.customerPaymentCategory union accepts the value.
    val customerPaymentCategory: String? = null,
    // Only populated when customerPaymentCategory == "B" (Loan
    // Customer); otherwise null. Server uses this to derive the
    // Cash (Balance) computed value on the booking detail view.
    val loanAmountRequested: Double? = null,
    // "Regular" | "Flexi" | "Special" — the Balance Payment Schedule plan
    // (Special only offered when the project has specialPaymentEnabled).
    val paymentPlan: String? = null,
    val freePayment: Boolean? = null,
    val allotmentDueAmount: Double? = null,
    val allotmentDueDate: String? = null,
    val secondPaymentAmount: Double? = null,
    val secondPaymentDate: String? = null,
    val thirdPaymentAmount: Double? = null,
    val thirdPaymentDate: String? = null,
    val fourthPaymentAmount: Double? = null,
    val fourthPaymentDate: String? = null,
    val preferredRegistrationDate: String? = null,
    val originalAvpStaffId: String? = null,
    val originalGmStaffId: String? = null,
    val originalSeniorManagerStaffId: String? = null,
    val originalBdoStaffId: String? = null,
    val originalTelecallerStaffId: String? = null,
    val aadhaar: String? = null,
    val aadhaarDocumentStorageId: String? = null,
    val aadhaarDocumentFileName: String? = null,
    val pan: String? = null,
    val panDocumentStorageId: String? = null,
    val panDocumentFileName: String? = null,
    val referenceName1: String? = null,
    val referenceMobile1: String? = null,
    val referenceProfession1: String? = null,
    val referenceName2: String? = null,
    val referenceMobile2: String? = null,
    val referenceProfession2: String? = null,
    val docPreparedIn: String? = null,
    val status: String? = null,
    val sourceType: String? = null,
    val sourceClientPlaceVisitId: String? = null,
    val sourceSiteVisitId: String? = null,
    // Booking follow-up: the backend spawns a booking_cp for this date/time to
    // service the booking (a visit to complete docs / collect). The POST
    // /api/bookings handler forwards these to spawnBookingCpFromSource.
    val bookingCpDate: String? = null,
    val bookingCpTime: String? = null,
    // Aadhaar back + CEF form front/back (web parity). The booking mutation +
    // schema accept these; the POST /api/bookings HTTP mapper must forward them.
    val aadhaarBackDocumentStorageId: String? = null,
    val aadhaarBackDocumentFileName: String? = null,
    val cefFormFrontDocumentStorageId: String? = null,
    val cefFormFrontDocumentFileName: String? = null,
    val cefFormBackDocumentStorageId: String? = null,
    val cefFormBackDocumentFileName: String? = null,
    // Dynamic / self-cash (category A / Flexi free-payment) schedule rows.
    val flexiPaymentSchedule: List<FlexiPaymentRow>? = null,
    // Resolved conversion/exchange credit applied to the payable chain.
    val conversionExchangeAmount: Double? = null,
    // When true the booking is inserted without kicking off the approval
    // workflow (stays a draft). Normal mobile submits leave this null.
    val skipApproval: Boolean? = null,
)

/** One row of the dynamic (self-cash / Flexi free-payment) balance schedule. */
data class FlexiPaymentRow(
    val amount: Double,
    val dueDate: String, // yyyy-MM-dd
)

data class CreateBookingResponse(
    val success: Boolean,
    val id: String? = null,
    val error: String? = null,
)

// ── Booking draft wire types ─────────────────────────────────────
// `sourceKey` is the stable string the backend uses to dedupe the
// per-staff scratchpad: stringified CP id, SV id, or "standalone".

data class BookingDraftSaveRequest(
    val sourceKey: String,
    val sourceCpVisitId: String? = null,
    val sourceSiteVisitId: String? = null,
    /** Opaque blob — the mobile owns the schema. */
    val draftJson: String,
)

data class BookingDraftSaveResponse(
    val success: Boolean,
    val id: String? = null,
    val updatedAt: Long? = null,
    val created: Boolean? = null,
    val error: String? = null,
)

data class BookingDraftPayload(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val staffId: String? = null,
    val sourceKey: String? = null,
    val sourceCpVisitId: String? = null,
    val sourceSiteVisitId: String? = null,
    val draftJson: String? = null,
    val updatedAt: Long? = null,
)

data class BookingDraftGetResponse(
    val success: Boolean = false,
    val draft: BookingDraftPayload? = null,
    val error: String? = null,
)

data class BookingDraftClearRequest(val sourceKey: String)

data class BookingDraftClearResponse(
    val success: Boolean = false,
    val deleted: Boolean? = null,
    val error: String? = null,
)

// ── Marketing: Bookings list (mobile) ──────────────────────────────────────
// Server enriches each row with projectName + plotNumber so the list card
// can render without secondary lookups.
data class Booking(
    @SerializedName("_id", alternate = ["id"]) val id: String,
    @SerializedName("_creationTime") val creationTime: Double? = null,
    val bookingRefNo: String? = null,
    val title: String? = null,
    val clientName: String? = null,
    val fatherSpouseName: String? = null,
    val dateOfBirth: String? = null,
    val anniversaryDate: String? = null,
    val mobileNumber: String? = null,
    val alternateNumbers: String? = null,
    val whatsappNumber: String? = null,
    val email: String? = null,
    val pincode: String? = null,
    val homeAddress: String? = null,
    val profession: String? = null,
    val designation: String? = null,
    val department: String? = null,
    val incomePerAnnum: String? = null,
    val officeName: String? = null,
    val officeAddress: String? = null,
    val officeArea: String? = null,
    val officePincode: String? = null,
    val state: String? = null,
    val district: String? = null,
    val location: String? = null,
    val officeMobile: String? = null,
    val officePhone: String? = null,
    val officeEmail: String? = null,
    val nationality: String? = null,
    val bookingDate: String? = null,                // yyyy-MM-dd
    val bookingType: String? = null,
    val conversionManualEntry: Boolean? = null,
    val manualConversionProjectName: String? = null,
    val manualConversionPlotNo: String? = null,
    val manualConversionCredit: Double? = null,
    val conversionNotes: String? = null,
    val sourceExchangeBookingId: String? = null,
    val exchangeManualEntry: Boolean? = null,
    val exchangeLookupProjectId: String? = null,
    val exchangeLookupPlotNo: String? = null,
    val exchangeConnectedMobileNumber: String? = null,
    val manualExchangeProjectName: String? = null,
    val manualExchangePlotNo: String? = null,
    val manualExchangeExtentSqft: Double? = null,
    val exchangeOldRegisteredValue: Double? = null,
    val exchangeNewValue: Double? = null,
    val exchangeBalancePayable: Double? = null,
    val exchangeNotes: String? = null,
    val cefNo: String? = null,
    val isDuplicateBooking: Boolean? = null,
    val isAgainstSV: Boolean? = null,
    val svName: String? = null,
    val svMobileNo: String? = null,
    val propertyType: String? = null,
    val bookingMode: String? = null,
    val clientSource: String? = null,
    val clientSourceName: String? = null,
    val clientSourceMobile: String? = null,
    val referralBenefit: String? = null,
    val bookingCost: Double? = null,
    val guidelineValue: Double? = null,
    val specialConsideration: Double? = null,
    val specialConsiderationReason: String? = null,
    val discountApprovedBy: String? = null,
    val specialConsiderationValidity: Double? = null,
    val promotionalOffers: String? = null,
    val promotionalOffersTnC: String? = null,
    val promotionalOfferValue: Double? = null,
    val offerValidityPeriod: Double? = null,
    val registrationCharges: Double? = null,
    val gstApplicable: Boolean? = null,
    val gstAmount: Double? = null,
    val documentCharges: Double? = null,
    val pattaCharges: Double? = null,
    val otherChargesApplicable: Boolean? = null,
    val otherCharges: Double? = null,
    val advanceAmount: Double? = null,
    val balanceAmount: Double? = null,
    val agreedAmount: Double? = null,
    val paymentMode: String? = null,
    val advanceTransactionId: String? = null,
    val advancePaymentProofStorageId: String? = null,
    val advancePaymentProofFileName: String? = null,
    val advanceInstrumentNo: String? = null,
    val advanceBankName: String? = null,
    val advanceBankBranch: String? = null,
    val advanceInstrumentDate: String? = null,
    // Mirrors the web Booking · Customer Payment Category dropdown.
    // Mobile sends just the letter ("A" / "B" / "C") so the server's
    // bookings.customerPaymentCategory union accepts the value.
    val customerPaymentCategory: String? = null,
    // Only populated when customerPaymentCategory == "B" (Loan
    // Customer); otherwise null. Server uses this to derive the
    // Cash (Balance) computed value on the booking detail view.
    val loanAmountRequested: Double? = null,
    // "Regular" | "Flexi" | "Special" — the Balance Payment Schedule plan
    // (Special only offered when the project has specialPaymentEnabled).
    val paymentPlan: String? = null,
    val freePayment: Boolean? = null,
    val allotmentDueAmount: Double? = null,
    val allotmentDueDate: String? = null,
    val secondPaymentAmount: Double? = null,
    val secondPaymentDate: String? = null,
    val thirdPaymentAmount: Double? = null,
    val thirdPaymentDate: String? = null,
    val fourthPaymentAmount: Double? = null,
    val fourthPaymentDate: String? = null,
    val preferredRegistrationDate: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val plotId: String? = null,
    val plotNo: String? = null,
    val plotNumber: String? = null,                  // server-enriched fallback
    /** draft | pending_confirmation | confirmed | cancelled */
    val status: String? = null,
    val approvalStage: String? = null,
    val sourceType: String? = null,                  // cp_visit | site_visit | walk_in
    val createdByStaffId: String? = null,
    val createdAt: Double? = null,
    val updatedAt: Double? = null,
    val aadhaar: String? = null,
    val aadhaarDocumentStorageId: String? = null,
    val aadhaarDocumentFileName: String? = null,
    val pan: String? = null,
    val panDocumentStorageId: String? = null,
    val panDocumentFileName: String? = null,
    val referenceName1: String? = null,
    val referenceMobile1: String? = null,
    val referenceProfession1: String? = null,
    val referenceName2: String? = null,
    val referenceMobile2: String? = null,
    val referenceProfession2: String? = null,
    val docPreparedIn: String? = null,
    val accountsTransactionId: String? = null,
    val accountsPaymentProofStorageId: String? = null,
    val accountsPaymentProofFileName: String? = null,
    val approvalRequest: BookingApprovalRequest? = null,
    val approvalWorkflow: BookingApprovalWorkflow? = null,
    val cancellationRequest: BookingApprovalRequest? = null,
    val cancellationApprovalStage: String? = null,
    val cancellationRequestedAt: Double? = null,
    val plot: BookingPlotDetail? = null,
    val sourceTelecallerStaff: BookingStaffBrief? = null,
    val sourceAvpStaff: BookingStaffBrief? = null,
)

data class BookingsListResponse(
    val success: Boolean,
    val total: Int? = null,
    val bookings: List<Booking> = emptyList(),
    val error: String? = null,
)

data class BookingDetailResponse(
    val success: Boolean,
    val booking: Booking? = null,
    val error: String? = null,
)

data class BookingApprovalHistory(
    val stepOrder: Int? = null,
    val action: String? = null,
    val approverName: String? = null,
    val comment: String? = null,
    val timestamp: String? = null,
)

data class BookingApprovalRequest(
    val requestedBy: String? = null,
    val requestedOn: String? = null,
    val currentStep: Int? = null,
    val totalSteps: Int? = null,
    val currentApproverId: String? = null,
    val currentApproverName: String? = null,
    val currentApproverRole: String? = null,
    val status: String? = null,
    val approvalHistory: List<BookingApprovalHistory> = emptyList(),
)

data class BookingApprovalWorkflow(
    val steps: List<BookingApprovalStep> = emptyList(),
)

data class BookingApprovalStep(
    val stepOrder: Int? = null,
    val approverRole: String? = null,
    val requiresTransactionId: Boolean? = null,
    val allowsPaymentProof: Boolean? = null,
)

data class BookingPlotDetail(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val unitNumber: String? = null,
    val plotNo: String? = null,
    val status: String? = null,
)

data class BookingStaffBrief(
    @SerializedName("_id", alternate = ["id"]) val id: String? = null,
    val name: String? = null,
)

data class UpdateBookingRequest(
    val title: String? = null,
    val clientName: String? = null,
    val fatherSpouseName: String? = null,
    val dateOfBirth: String? = null,
    val anniversaryDate: String? = null,
    val mobileNumber: String? = null,
    val alternateNumbers: String? = null,
    val whatsappNumber: String? = null,
    val email: String? = null,
    val pincode: String? = null,
    val homeAddress: String? = null,
    val profession: String? = null,
    val designation: String? = null,
    val incomePerAnnum: String? = null,
    val officeName: String? = null,
    val officeAddress: String? = null,
    val state: String? = null,
    val district: String? = null,
    val location: String? = null,
    val officeMobile: String? = null,
    val officePhone: String? = null,
    val officeEmail: String? = null,
    val nationality: String? = null,
    val bookingType: String? = null,
    val cefNo: String? = null,
    val bookingDate: String? = null,
    val isDuplicateBooking: Boolean? = null,
    val isAgainstSV: Boolean? = null,
    val propertyType: String? = null,
    val bookingMode: String? = null,
    val plotNo: String? = null,
    val bookingCost: Double? = null,
    val guidelineValue: Double? = null,
    val specialConsideration: Double? = null,
    val specialConsiderationReason: String? = null,
    val discountApprovedBy: String? = null,
    val specialConsiderationValidity: Double? = null,
    val promotionalOffers: String? = null,
    val promotionalOffersTnC: String? = null,
    val promotionalOfferValue: Double? = null,
    val offerValidityPeriod: Double? = null,
    val agreedAmount: Double? = null,
    val registrationCharges: Double? = null,
    val gstApplicable: Boolean? = null,
    val gstAmount: Double? = null,
    val documentCharges: Double? = null,
    val pattaCharges: Double? = null,
    val otherChargesApplicable: Boolean? = null,
    val otherCharges: Double? = null,
    val advanceAmount: Double? = null,
    val balanceAmount: Double? = null,
    val paymentMode: String? = null,
    // Mirrors the web Booking · Customer Payment Category dropdown.
    // Mobile sends just the letter ("A" / "B" / "C") so the server's
    // bookings.customerPaymentCategory union accepts the value.
    val customerPaymentCategory: String? = null,
    // Only populated when customerPaymentCategory == "B" (Loan
    // Customer); otherwise null. Server uses this to derive the
    // Cash (Balance) computed value on the booking detail view.
    val loanAmountRequested: Double? = null,
    // "Regular" | "Flexi" | "Special" — the Balance Payment Schedule plan
    // (Special only offered when the project has specialPaymentEnabled).
    val paymentPlan: String? = null,
    val freePayment: Boolean? = null,
    val allotmentDueAmount: Double? = null,
    val allotmentDueDate: String? = null,
    val secondPaymentAmount: Double? = null,
    val secondPaymentDate: String? = null,
    val thirdPaymentAmount: Double? = null,
    val thirdPaymentDate: String? = null,
    val fourthPaymentAmount: Double? = null,
    val fourthPaymentDate: String? = null,
    val preferredRegistrationDate: String? = null,
    val aadhaar: String? = null,
    val pan: String? = null,
    val referenceName1: String? = null,
    val referenceMobile1: String? = null,
    val referenceProfession1: String? = null,
    val referenceName2: String? = null,
    val referenceMobile2: String? = null,
    val referenceProfession2: String? = null,
    val docPreparedIn: String? = null,
    val status: String? = null,
)

data class BookingApproveRequest(
    val comment: String? = null,
    val accountsTransactionId: String? = null,
    val accountsPaymentProofStorageId: String? = null,
    val accountsPaymentProofFileName: String? = null,
)

data class BookingRejectRequest(
    val rejectionReason: String,
)

data class BookingActionResponse(
    val success: Boolean,
    val booking: Booking? = null,
    val error: String? = null,
)

// ── Land Procurement: Inspection (mobile) ──────────────────────────────────
// Convex `landProperties` row enriched on the server with a derived inspector
// status (`not_started` / `in_progress` / `completed`) and the calling
// inspector's per-staff report id when one exists.
data class InspectionListItem(
    @SerializedName("_id") val propertyId: String,
    val referenceNo: String? = null,
    val totalArea: Double? = null,
    val areaUnit: String? = null,
    val inspectionDate: String? = null,
    val village: String? = null,
    val taluk: String? = null,
    val district: String? = null,
    val locality: String? = null,
    val city: String? = null,
    val fullAddress: String? = null,
    val pincode: String? = null,
    val referrerContact: String? = null,
    val surveyNo: String? = null,
    val propertyType: String? = null,
    val derivedInspectionStatus: String? = null,
    // pending / accepted / date_change_requested / date_change_approved /
    // date_change_rejected (null on legacy rows). Gates the inspection form:
    // only "accepted" lets the inspector fill it in.
    val inspectionAcceptanceStatus: String? = null,
    val inspectionDateChangeVpRespondedAt: String? = null,
    val reportId: String? = null,
    // VP final review of the submitted inspection. "approved" → form
    // locks to view-only on mobile (the inspector can still see what was
    // submitted but can no longer edit). "hold" / "rejected" keep the
    // form editable so the inspector can address feedback.
    val vpInspectionStatus: String? = null,
)

data class InspectionListResponse(
    val success: Boolean,
    val total: Int = 0,
    val items: List<InspectionListItem> = emptyList(),
    val error: String? = null,
)

// Returned by `/api/land/inspections/get`. `report` is `{}` for properties
// where the inspector hasn't saved anything yet — Gson maps that to all
// fields being null.
// One Area-tab nearby place: school / college / hospital / mall. Both keys
// are required strings on the server (v.string()), so default to "" rather
// than null when building a save payload.
data class InspectionAreaEntry(
    val name: String = "",
    val distance: String = "",
)

// One competitor project. Mirrors the web's landCompetitors row so a phone
// submission shows up on the web competitor list unchanged. approvalType is
// the server union ("cmda"/"dtcp"/"panchayat") or null; price units are sent
// lowercased to match the web's stored values.
data class InspectionCompetitor(
    val promoterName: String? = null,
    val projectName: String? = null,
    val location: String? = null,
    val latLong: String? = null,
    val extentUnits: String? = null,
    val approvalType: String? = null,
    val amenities: String? = null,
    val amenitiesList: List<String>? = null,
    val currentStage: String? = null,
    val distanceFromProject: String? = null,
    val distanceFromBusStand: String? = null,
    val distanceFromRailway: String? = null,
    val distanceFromPublic: String? = null,
    val distanceFromPrivate: String? = null,
    val actualPrice: Double? = null,
    val actualPriceUnit: String? = null,
    val finalPrice: Double? = null,
    val finalPriceUnit: String? = null,
)

data class InspectionReportData(
    val inspectionDate: String? = null,
    val customerName: String? = null,
    val conductNo: String? = null,
    val surveyNo: String? = null,
    val siteLocation: String? = null,
    val exactLocation: String? = null,
    val landmark: String? = null,
    val latLong: String? = null,
    val population: String? = null,
    val accessibilityWidth: String? = null,
    val accessibilityWidthUnit: String? = null,
    val electricity: String? = null,
    val eConnectionToLand: String? = null,
    @com.google.gson.annotations.SerializedName(value = "eConnectionPhases", alternate = ["phases", "eConnectionPhase", "howManyPhases"])
    val eConnectionPhases: String? = null,
    val telecom: String? = null,
    val railwayStationDistance: String? = null,
    val busStopDistance: String? = null,
    val roadType: List<String>? = null,
    val schoolExists: Boolean? = null,
    val schoolEntries: List<InspectionAreaEntry>? = null,
    val collegeExists: Boolean? = null,
    val collegeEntries: List<InspectionAreaEntry>? = null,
    val hospitalExists: Boolean? = null,
    val hospitalEntries: List<InspectionAreaEntry>? = null,
    val mallExists: Boolean? = null,
    val mallEntries: List<InspectionAreaEntry>? = null,
    val marketExists: Boolean? = null,
    val marketEntries: List<InspectionAreaEntry>? = null,
    val presentDemand: List<String>? = null,
    val futureDemand: List<String>? = null,
    val targetClients: List<String>? = null,
    val landlordPrice: Double? = null,
    val landlordPriceUnit: String? = null,
    val recommendationPrice: Double? = null,
    val recommendationPriceUnit: String? = null,
    val priceCanSell: Double? = null,
    val priceCanSellUnit: String? = null,
    val conclusion: String? = null,
)

data class InspectionGetResponse(
    val success: Boolean,
    val property: InspectionListItem? = null,
    val report: InspectionReportData? = null,
    val competitors: List<InspectionCompetitor>? = null,
    val error: String? = null,
)

// Send only the keys the user actually touched — nulls are dropped on the
// server side, so partial saves don't wipe earlier values from the web.
data class InspectionSaveRequest(
    val propertyId: String,
    val inspectionDate: String? = null,
    val customerName: String? = null,
    val conductNo: String? = null,
    val surveyNo: String? = null,
    val siteLocation: String? = null,
    val exactLocation: String? = null,
    val landmark: String? = null,
    val latLong: String? = null,
    val population: String? = null,
    val accessibilityWidth: String? = null,
    val accessibilityWidthUnit: String? = null,
    val electricity: String? = null,
    val eConnectionToLand: String? = null,
    @com.google.gson.annotations.SerializedName(value = "eConnectionPhases", alternate = ["phases", "eConnectionPhase", "howManyPhases"])
    val eConnectionPhases: String? = null,
    val telecom: String? = null,
    val railwayStationDistance: String? = null,
    val busStopDistance: String? = null,
    val roadType: List<String>? = null,
    val schoolExists: Boolean? = null,
    val schoolEntries: List<InspectionAreaEntry>? = null,
    val collegeExists: Boolean? = null,
    val collegeEntries: List<InspectionAreaEntry>? = null,
    val hospitalExists: Boolean? = null,
    val hospitalEntries: List<InspectionAreaEntry>? = null,
    val mallExists: Boolean? = null,
    val mallEntries: List<InspectionAreaEntry>? = null,
    val marketExists: Boolean? = null,
    val marketEntries: List<InspectionAreaEntry>? = null,
    val presentDemand: List<String>? = null,
    val futureDemand: List<String>? = null,
    val targetClients: List<String>? = null,
    val landlordPrice: Double? = null,
    val landlordPriceUnit: String? = null,
    val recommendationPrice: Double? = null,
    val recommendationPriceUnit: String? = null,
    val priceCanSell: Double? = null,
    val priceCanSellUnit: String? = null,
    val conclusion: String? = null,
    val competitors: List<InspectionCompetitor>? = null,
)

data class InspectionSaveResponse(
    val success: Boolean,
    val reportId: String? = null,
    val error: String? = null,
)

data class InspectionAcceptRequest(
    val propertyId: String,
)

data class InspectionRescheduleRequest(
    val propertyId: String,
    val requestedDate: String,
    val remarks: String? = null,
)

data class InspectionActionResponse(
    val success: Boolean,
    val error: String? = null,
)

// One legal-clearance verification query, flattened from a property's
// landLegalClearance.verificationQueries (the web "Verification & Queries"
// section). propertyId + queryIndex identify the row for updates.
data class QueryListItem(
    val propertyId: String,
    val referenceNo: String? = null,
    val queryIndex: Int = 0,
    val query: String? = null,
    val remarks: String? = null,
    val resolved: Boolean = false,
    val createdOn: String? = null,
)

data class QueryListResponse(
    val success: Boolean,
    val total: Int = 0,
    val items: List<QueryListItem> = emptyList(),
    val error: String? = null,
)

data class QueryUpdateRequest(
    val propertyId: String,
    val queryIndex: Int,
    val remarks: String? = null,
    val resolved: Boolean? = null,
)

// ── Fines & Deductions ──
data class FinesListResponse(
    val success: Boolean = false,
    val fines: List<FineDeductionItem> = emptyList(),
    val error: String? = null,
)

data class FineDeductionItem(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val staffName: String = "",
    val employeeId: String = "",
    val department: String = "",
    val typeName: String = "",
    val amount: Double = 0.0,
    val month: Int = 0,
    val year: Int = 0,
    val monthName: String = "",
    val status: String = "active",
    val notes: String? = null,
    val photoUrl: String? = null,
    val staffPhotoUrl: String? = null,
    val createdAt: String? = null,
)

// ── VP / Management Dashboard models ──
data class OverviewDashboardResponse(
    val success: Boolean = false,
    val date: String? = null,
    val present: Int = 0,
    val absent: Int = 0,
    val totalStaff: Int = 0,
    val notPunchedIn: Int = 0,
    val incomingCalls: Int = 0,
    val outboundCalls: Int = 0,
    val totalCalls: Int = 0,
    val cpVisitsFixed: Int = 0,
    val cpVisitsCompleted: Int = 0,
    val svVisitsFixed: Int = 0,
    val svVisitsCompleted: Int = 0,
    val collectionTotal: Double = 0.0,
    val collectionCount: Int = 0,
    val bookingCount: Int = 0,
    val registrationCount: Int = 0,
    // Extended HR + lead-temperature fields — nullable so the overview tiles
    // render "–" until the extended /api/dashboard/overview route is deployed.
    val leaveApproved: Int? = null,
    val weekOff: Int? = null,
    val permissionCount: Int? = null,
    val wfhApproved: Int? = null,
    val leadsHot: Int? = null,
    val leadsWarm: Int? = null,
    val leadsCold: Int? = null,
    val error: String? = null,
)

data class MobileDashboardResponse(
    val success: Boolean = false,
    val date: String? = null,
    val totalCalls: Int = 0,
    val incomingCalls: Int = 0,
    val outboundCalls: Int = 0,
    val hot: Int = 0,
    val warm: Int = 0,
    val cold: Int = 0,
    val cpVisitsFixed: Int = 0,
    val svVisitsFixed: Int = 0,
    val totalStaff: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val leave: Int = 0,
    // Same weekday one week earlier — the baseline for the tiles' trend pills.
    // Nullable on purpose: a backend that predates these fields returns nothing
    // at all (Gson → null) and the app hides the pills rather than inventing a
    // delta. Do NOT give these defaults.
    val prevTotalCalls: Int? = null,
    val prevIncomingCalls: Int? = null,
    val prevOutboundCalls: Int? = null,
    val prevHot: Int? = null,
    val prevWarm: Int? = null,
    val prevCold: Int? = null,
    val error: String? = null,
)

data class DashboardCallRow(
    val id: String,
    val phoneNumber: String? = null,
    val callType: String? = null,
    val status: String? = null,
    val agent: String? = null,
    val duration: String? = null,
    val talkTime: String? = null,
    val time: String? = null,
)

data class DashboardCallsResponse(
    val success: Boolean = false,
    val calls: List<DashboardCallRow> = emptyList(),
    val error: String? = null,
)

data class DashboardRegistrationRow(
    val id: String,
    val clientName: String? = null,
    val ownerName: String? = null,
    val status: String? = null,
    val completedDate: String? = null,
    val scheduledDate: String? = null,
    val notes: String? = null,
)

data class DashboardRegistrationsResponse(
    val success: Boolean = false,
    val registrations: List<DashboardRegistrationRow> = emptyList(),
    val error: String? = null,
)

data class CreateFineRequest(
    val employeeId: String,
    val amount: Double,
    val month: Int,
    val year: Int,
    val customTypeName: String? = null,
    val typeId: String? = null,
    val notes: String? = null,
    val photoStorageId: String? = null,
    val photoFileName: String? = null,
)

data class CreateFineResponse(
    val success: Boolean = false,
    val fineId: String? = null,
    val error: String? = null,
)

// ── Front Desk invitation lookup / check-in ──────────────────────────────────
data class InvitationLookupResponse(
    val success: Boolean = false,
    val invitation: InvitationDetail? = null,
    val error: String? = null,
)

data class InvitationDetail(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val token: String?,
    val visitorName: String?,
    val visitorPhone: String?,
    val visitorEmail: String?,
    val visitorCompany: String?,
    val visitorAge: Int?,
    val additionalVisitors: List<AdditionalVisitor>? = emptyList(),
    val categoryName: String?,
    val purposeName: String?,
    val hostName: String?,
    val hostDepartment: String?,
    val expectedDate: String?,
    val expectedTimeFrom: String?,
    val expectedTimeTo: String?,
    val meetingNotes: String?,
    val status: String?,
    // Live check-in state so the scanner can toggle between Confirm Admission,
    // Check Out, and a checked-out summary. checkinState: "in" | "out" | null.
    val checkinId: String? = null,
    val checkinState: String? = null,
    val checkinAt: Long? = null,
    val checkoutAt: Long? = null,
    val passNumber: String? = null,
)

data class AdditionalVisitor(
    val name: String?,
    val phone: String?,
    val age: Int?,
    val email: String?,
)

data class CheckinInvitationRequest(
    val invitationId: String,
    val notes: String? = null,
)

data class CheckinInvitationResponse(
    val success: Boolean = false,
    val passNumber: String? = null,
    val error: String? = null,
)

data class CheckoutInvitationRequest(
    val invitationId: String,
)

data class CheckoutInvitationResponse(
    val success: Boolean = false,
    val checkoutAt: Long? = null,
    val error: String? = null,
)

// ── Issues (project-scoped) ──────────────────────────────────────────────────
data class CreateProjectIssueRequest(
    val projectId: String,
    val title: String,
    val description: String? = null,
    val audioStorageId: String? = null,
    val audioFileName: String? = null,
    val audioFileType: String? = null,
    val audioFileSize: Long? = null,
    val audioDurationSeconds: Long? = null,
)

data class CreateIssueResponse(
    val success: Boolean = false,
    @SerializedName("id") val issueId: String? = null,
    val error: String? = null,
)

data class IssuesListResponse(
    val success: Boolean = false,
    val issues: List<IssueItem> = emptyList(),
    val error: String? = null,
)

data class IssueItem(
    @SerializedName("_id", alternate = ["id"]) val id: String?,
    val projectId: String?,
    val title: String?,
    val description: String?,
    val audioStorageId: String?,
    val audioUrl: String?,
    // Convex returns these as JSON numbers that may carry a fractional part
    // (e.g. _creationTime-derived timestamps). Parsing them as Double avoids a
    // Gson NumberFormatException that would fail the WHOLE response and leave
    // the Issues list silently empty.
    val audioDurationMs: Double?,
    val status: String?,
    val createdAt: Double?,
)
