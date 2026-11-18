package com.lollo1344.cryptionchat

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    @FormUrlEncoded
    @POST("api.php")
    fun unifiedPoll(
        @Field("action") action: String = "unified_poll",
        @Field("user_id") userId: Int,
        @Field("last_timestamp") lastTimestamp: String,
        @Field("active_call_id") activeCallId: Int? = null,
        @Field("device_id") deviceId: String,
        @Field("last_message_id") lastMessageId: Int = 0
    ): Call<ApiResponse<UnifiedPollData>>

    @FormUrlEncoded
    @POST("api.php")
    fun registerRequestCode(
        @Field("action") action: String = "register_request_code",
        @Field("username") username: String,
        @Field("email") email: String,
        @Field("password") password: String,
        @Field("device_id") deviceId: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun registerVerifyCode(
        @Field("action") action: String = "register_verify_code",
        @Field("username") username: String,
        @Field("email") email: String,
        @Field("password") password: String,
        @Field("code") code: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun login(
        @Field("action") action: String = "login",
        @Field("email") email: String,
        @Field("password") password: String,
        @Field("device_id") deviceId: String
    ): Call<LoginResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun requestPasswordReset(
        @Field("action") action: String = "request_password_reset",
        @Field("email") email: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun submitPasswordReset(
        @Field("action") action: String = "submit_password_reset",
        @Field("email") email: String,
        @Field("code") code: String,
        @Field("new_password") newPassword: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun deleteAccount(
        @Field("action") action: String = "delete_account",
        @Field("user_id") userId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun searchUsers(
        @Field("action") action: String = "search_users",
        @Field("user_id") userId: Int,
        @Field("query") query: String
    ): Call<SearchUserResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun changeUsername(
        @Field("action") action: String = "change_username",
        @Field("user_id") userId: Int,
        @Field("new_username") newUsername: String,
        @Field("password") password: String
    ): Call<ChangeUsernameResponse>

    @Multipart
    @POST("api.php")
    fun uploadProfilePicture(
        @Part("action") action: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part profilePicture: MultipartBody.Part
    ): Call<ProfilePictureResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun removeProfilePicture(
        @Field("action") action: String = "remove_profile_picture",
        @Field("user_id") userId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun setUserStatus(
        @Field("action") action: String = "set_user_status",
        @Field("user_id") userId: Int,
        @Field("status") status: String,
        @Field("hide_online_status") hideOnlineStatus: Boolean? = null
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("update_version.php")
    fun updateUserVersion(
        @Field("user_id") userId: Int,
        @Field("version_name") versionName: String,
        @Field("version_code") versionCode: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun startOrGetPrivateChat(
        @Field("action") action: String = "start_or_get_private_chat",
        @Field("user1_id") user1Id: Int,
        @Field("user2_id") user2Id: Int,
        @Field("e2ee_version") e2eeVersion: Int = 2
    ): Call<StartChatResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun createGroup(
        @Field("action") action: String = "create_group",
        @Field("user_id") userId: Int,
        @Field("group_name") groupName: String,
        @Field("e2ee_version") e2eeVersion: Int = 2
    ): Call<CreateGroupResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun joinGroup(
        @Field("action") action: String = "join_group",
        @Field("user_id") userId: Int,
        @Field("code") code: String
    ): Call<JoinGroupResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun getUserGroups(
        @Field("action") action: String = "get_user_groups",
        @Field("user_id") userId: Int
    ): Call<GetGroupsResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun leaveGroup(
        @Field("action") action: String = "leave_group",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun deleteGroup(
        @Field("action") action: String = "delete_group",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun createInviteLink(
        @Field("action") action: String = "create_invite_link",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int
    ): Call<CreateInviteResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun getGroupMembers(
        @Field("action") action: String = "get_group_members",
        @Field("group_id") groupId: Int
    ): Call<GetGroupMembersResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun regenerateKeys(
        @Field("action") action: String = "regenerate_keys",
        @Field("group_id") groupId: Int,
        @Field("user_id") userId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun setAdminStatus(
        @Field("action") action: String = "set_admin_status",
        @Field("group_id") groupId: Int,
        @Field("admin_user_id") adminUserId: Int,
        @Field("target_user_id") targetUserId: Int,
        @Field("is_admin") isAdmin: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun kickUser(
        @Field("action") action: String = "kick_user",
        @Field("group_id") groupId: Int,
        @Field("admin_id") adminId: Int,
        @Field("user_to_kick_id") userToKickId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun changeGroupName(
        @Field("action") action: String = "change_group_name",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int,
        @Field("new_name") newName: String
    ): Call<ChangeGroupNameResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun setGroupChatMode(
        @Field("action") action: String = "set_group_chat_mode",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int,
        @Field("admins_only_chat") adminsOnlyChat: Boolean
    ): Call<BaseResponse>

    @Multipart
    @POST("api.php")
    fun uploadGroupPicture(
        @Part("action") action: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part("group_id") groupId: RequestBody,
        @Part groupPicture: MultipartBody.Part
    ): Call<GroupPictureResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun removeGroupPicture(
        @Field("action") action: String = "remove_group_picture",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun getMessages(
        @Field("action") action: String = "get_messages",
        @Field("group_id") groupId: Int,
        @Field("user_id") userId: Int,
        @Field("cache_buster") cacheBuster: Long
    ): Call<ApiResponse<GetMessagesData>>

    @FormUrlEncoded
    @POST("api.php")
    fun sendMessage(
        @Field("action") action: String = "send_message",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int,
        @Field("encrypted_message") encryptedMessage: String,
        @Field("reply_to_message_id") replyToMessageId: Int?,
        @Field("is_view_once") isViewOnce: Boolean?,
        @Field("mentioned_users_json") mentionedUsersJson: String?
    ): Call<BaseResponse>

    @Multipart
    @POST("api.php")
    fun sendFile(
        @Part("action") action: RequestBody,
        @Part("user_id") userId: RequestBody,
        @Part("group_id") groupId: RequestBody,
        @Part("encrypted_metadata") encryptedMetadata: RequestBody,
        @Part encryptedFile: MultipartBody.Part,
        @Part("reply_to_message_id") replyToMessageId: RequestBody?,
        @Part("is_view_once") isViewOnce: RequestBody?,
        @Part("mentioned_users_json") mentionedUsersJson: RequestBody?,
        @Part("message_type") messageType: RequestBody?
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun editMessage(
        @Field("action") action: String = "edit_message",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int,
        @Field("encrypted_new_message") encryptedNewMessage: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun deleteMessage(
        @Field("action") action: String = "delete_message",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun deleteZkMessage(
        @Field("action") action: String = "delete_zk_message",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun deleteAllMessages(
        @Field("action") action: String = "delete_all_messages",
        @Field("group_id") groupId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun setAutoDelete(
        @Field("action") action: String = "set_auto_delete",
        @Field("group_id") groupId: Int,
        @Field("minutes") minutes: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun markMessagesAsRead(
        @Field("action") action: String = "mark_messages_as_read",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun markSpecificMessagesAsRead(
        @Field("action") action: String = "mark_specific_messages_as_read",
        @Field("user_id") userId: Int,
        @Field("message_ids") messageIdsJson: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun checkNewMessages(
        @Field("action") action: String = "check_new_messages",
        @Field("user_id") userId: Int,
        @Field("last_timestamp") lastTimestamp: String
    ): Call<ApiResponse<List<NewMessageNotification>>>

    @Headers("Cache-Control: no-cache")
    @GET("update.json")
    fun checkForUpdates(@Query("t") timestamp: Long): Call<UpdateInfoResponse>

    @Headers("Cache-Control: no-cache")
    @GET("beta_update.json")
    fun checkForBetaUpdates(@Query("t") timestamp: Long): Call<BetaUpdateResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun updateTypingStatus(
        @Field("action") action: String = "update_typing_status",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int,
        @Field("is_typing") isTyping: Boolean
    ): Call<BaseResponse>

    @Streaming
    @Headers("Connection: close", "Accept-Encoding: identity")
    @GET
    fun downloadFile(
        @Url fileUrl: String,
        @Header("Range") range: String? = null
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("send_feedback.php")
    fun sendFeedback(
        @Field("user_id") userId: Int,
        @Field("username") username: String,
        @Field("feedback_text") feedbackText: String,
        @Field("app_version") appVersion: String,
        @Field("device_model") deviceModel: String,
        @Field("device_brand") deviceBrand: String,
        @Field("os_version") osVersion: String
    ): Call<BaseResponse>

    @GET("get_broadcast.php")
    fun getBroadcastMessage(): Call<ApiResponse<BroadcastMessageData>>

    @FormUrlEncoded
    @POST("api.php")
    fun reactToMessage(
        @Field("action") action: String = "react_to_message",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int,
        @Field("reaction_emoji") reactionEmoji: String?
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun getMessageReadBy(
        @Field("action") action: String = "get_message_read_by",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int
    ): Call<GetReadReceiptsResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun markViewOnceOpened(
        @Field("action") action: String = "mark_view_once_opened",
        @Field("user_id") userId: Int,
        @Field("message_id") messageId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun initiateCall(
        @Field("action") action: String = "initiate_call",
        @Field("caller_id") callerId: Int,
        @Field("receiver_id") receiverId: Int,
        @Field("group_id") groupId: Int
    ): Call<CallResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun sendCallSignal(
        @Field("action") action: String = "send_signal",
        @Field("call_id") callId: Int,
        @Field("sender_id") senderId: Int,
        @Field("type") type: String,
        @Field("payload") payload: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun pollCallSignals(
        @Field("action") action: String = "poll_signals",
        @Field("user_id") userId: Int,
        @Field("call_id") callId: Int
    ): Call<SignalResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun executeServerPanic(
        @Field("action") action: String = "execute_server_panic",
        @Field("user_id") userId: Int,
        @Field("delete_account") deleteAccount: Boolean
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun checkIncomingCalls(
        @Field("action") action: String = "check_calls",
        @Field("user_id") userId: Int
    ): Call<IncomingCallResponse>

    @GET("predefinedimage.json")
    fun getPredefinedBackgrounds(): Call<PredefinedImagesResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun endCall(
        @Field("action") action: String = "end_call",
        @Field("call_id") callId: Int,
        @Field("user_id") userId: Int
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun updatePrivacySettings(
        @Field("action") action: String = "update_privacy_settings",
        @Field("user_id") userId: Int,
        @Field("send_read_receipts") sendReadReceipts: Boolean,
        @Field("hide_online_status") hideOnlineStatus: Boolean? = null
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun updateBetaStatus(
        @Field("action") action: String = "update_beta_status",
        @Field("user_id") userId: Int,
        @Field("is_beta_tester") isBetaTester: Boolean
    ): Call<BaseResponse>

    // --- E2EE v2: X3DH PREKEY BUNDLE & SENDER KEY DISTRIBUTIONS ---

    @FormUrlEncoded
    @POST("api.php")
    fun uploadKeyBundle(
        @Field("action") action: String = "upload_key_bundle",
        @Field("user_id") userId: Int,
        @Field("identity_sign_key") identitySignKey: String,
        @Field("identity_dh_key") identityDhKey: String,
        @Field("identity_dh_sig") identityDhSig: String,
        @Field("spk_id") spkId: Int,
        @Field("spk_pub") spkPub: String,
        @Field("spk_sig") spkSig: String,
        @Field("spk_created_at") spkCreatedAt: Long,
        @Field("opks_json") opksJson: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun uploadPrekeys(
        @Field("action") action: String = "upload_prekeys",
        @Field("user_id") userId: Int,
        @Field("opks_json") opksJson: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun getPrekeyCount(
        @Field("action") action: String = "get_prekey_count",
        @Field("user_id") userId: Int
    ): Call<ApiResponse<PreKeyCountData>>

    @FormUrlEncoded
    @POST("api.php")
    fun getPrekeyBundle(
        @Field("action") action: String = "get_prekey_bundle",
        @Field("user_id") userId: Int,
        @Field("target_user_id") targetUserId: Int
    ): Call<ApiResponse<PreKeyBundleData>>

    @FormUrlEncoded
    @POST("api.php")
    fun getIdentityKeys(
        @Field("action") action: String = "get_identity_keys",
        @Field("user_id") userId: Int,
        @Field("target_user_ids_json") targetUserIdsJson: String
    ): Call<ApiResponse<List<PeerIdentityKeyData>>>

    @FormUrlEncoded
    @POST("api.php")
    fun sendKeyDistribution(
        @Field("action") action: String = "send_key_distribution",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int,
        @Field("recipient_id") recipientId: Int,
        @Field("epoch") epoch: Int = 1,
        @Field("dist_type") distType: String = "sender_key",
        @Field("payload") payload: String
    ): Call<BaseResponse>

    @FormUrlEncoded
    @POST("api.php")
    fun fetchKeyDistributions(
        @Field("action") action: String = "fetch_key_distributions",
        @Field("user_id") userId: Int,
        @Field("group_id") groupId: Int = 0
    ): Call<ApiResponse<List<KeyDistributionItem>>>

    @FormUrlEncoded
    @POST("api.php")
    fun ackKeyDistribution(
        @Field("action") action: String = "ack_key_distribution",
        @Field("user_id") userId: Int,
        @Field("distribution_ids_json") distributionIdsJson: String
    ): Call<BaseResponse>

}