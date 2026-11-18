package com.lollo1344.cryptionchat

import com.google.gson.annotations.SerializedName

// --- CLASSI DI UTILITÀ GENERALE ---

data class FileMetadata(
    @SerializedName("name") val name: String,
    @SerializedName("mime") val mime: String,
    @SerializedName("size") val size: Long,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("caption") val caption: String? = null,
    @SerializedName("file_key") val fileKeyHex: String? = null,
    @SerializedName("file_iv") val fileIvHex: String? = null,
    @SerializedName("msg_type") val msgType: String? = null
)

// --- RISPOSTE API DI BASE ---

data class BaseResponse(
    val status: String,
    val message: String
)

data class ApiResponse<T>(
    val status: String,
    val message: String,
    val data: T?
)

// --- POLLING UNIFICATO ---

data class UnifiedPollData(
    @SerializedName("incoming_call") val incomingCall: IncomingCallDetails?,
    @SerializedName("messages") val messages: List<NewMessageNotification>?,
    @SerializedName("signals") val signals: List<SignalData>?,
    @SerializedName("call_ended") val callEnded: Boolean?,
    @SerializedName("has_key_distributions") val hasKeyDistributions: Boolean? = null
)

data class IncomingCallDetails(
    @SerializedName("call_id") val callId: Int,
    @SerializedName("caller_id") val callerId: Int,
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("caller_username") val callerUsername: String,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?
)

// --- AUTENTICAZIONE E UTENTE ---

data class LoginResponse(
    val status: String,
    val message: String,
    val data: UserData?
)

data class UserData(
    @SerializedName("user_id") val userId: Int,
    val username: String,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?,
    val token: String?,
    @SerializedName("is_beta_tester") val isBetaTester: Boolean?
)

data class User(
    val id: Int,
    val username: String,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?
)

data class SearchUserResponse(
    val status: String,
    val message: String,
    val data: List<User>?
)

data class ChangeUsernameResponse(
    val status: String,
    val message: String,
    val data: NewUsernameData?
)

data class NewUsernameData(
    @SerializedName("new_username") val newUsername: String
)

data class ProfilePictureResponse(
    val status: String,
    val message: String,
    val data: ProfilePictureUrl?
)

data class ProfilePictureUrl(
    @SerializedName("profile_picture_url") val profilePictureUrl: String
)

// --- CHAT E GRUPPI ---

data class StartChatResponse(
    val status: String,
    val message: String,
    val data: ChatInfo?
)

data class ChatInfo(
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("group_name") val groupName: String,
    @SerializedName("is_private_chat") val isPrivateChat: Boolean
)

data class CreateGroupResponse(
    val status: String,
    val message: String,
    val data: GroupIdCode?
)

data class GroupIdCode(
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("group_code") val groupCode: String
)

data class JoinGroupResponse(
    val status: String,
    val message: String,
    val data: GroupId?
)

data class GroupId(
    @SerializedName("group_id") val groupId: Int
)

data class CreateInviteResponse(
    val status: String,
    val message: String,
    val data: InviteData?
)

data class InviteData(
    @SerializedName("invite_code") val inviteCode: String
)

data class GetGroupsResponse(
    val status: String,
    val message: String,
    val data: List<Group>?
)

data class Group(
    val id: Int,
    @SerializedName("group_name") val groupName: String,
    @SerializedName("group_code") val groupCode: String?,
    @SerializedName("is_admin") val isAdminField: Any?,
    @SerializedName("is_private_chat") val isPrivateChat: Boolean,
    @SerializedName("unread_count") val unreadCount: Int,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?,
    @SerializedName("group_picture_url") val groupPictureUrl: String?,
    @SerializedName("last_message_timestamp") val lastMessageTimestamp: String? = null,
    @SerializedName("last_message") val lastMessage: String? = null,
    @SerializedName("is_online") val isOnline: Boolean? = false,
    @SerializedName("membership_epoch") val membershipEpoch: Int = 1,
    @SerializedName("e2ee_version") val e2eeVersion: Int = 1
) {
    val isAdmin: Boolean
        get() = when (isAdminField) {
            is Boolean -> isAdminField
            is Number -> isAdminField.toInt() == 1
            is String -> isAdminField == "1" || isAdminField.lowercase() == "true"
            else -> false
        }
}

data class ChangeGroupNameResponse(
    val status: String,
    val message: String,
    val data: NewGroupNameData?
)

data class NewGroupNameData(
    @SerializedName("new_name") val newName: String
)

data class GroupPictureResponse(
    val status: String,
    val message: String,
    val data: GroupPictureUrl?
)

data class GroupPictureUrl(
    @SerializedName("group_picture_url") val groupPictureUrl: String
)

data class GetGroupMembersResponse(
    val status: String,
    val message: String,
    val data: List<GroupMember>?
)

data class GroupMember(
    @SerializedName("user_id") val userId: Int,
    val username: String,
    @SerializedName("is_admin") val isAdminField: Any?,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?,
    @SerializedName("last_seen") val lastSeen: String?,
    @SerializedName("is_online") val isOnline: Boolean
) {
    val isAdmin: Boolean
        get() = when (isAdminField) {
            is Boolean -> isAdminField
            is Number -> isAdminField.toInt() == 1
            is String -> isAdminField == "1" || isAdminField.lowercase() == "true"
            else -> false
        }
}

// --- MESSAGGI ---

data class GetMessagesData(
    val messages: List<Message>,
    @SerializedName("auto_delete_minutes") val autoDeleteMinutes: Int,
    @SerializedName("typing_users") val typingUsers: List<String>,
    @SerializedName("is_current_user_admin") val isCurrentUserAdminField: Any?,
    @SerializedName("group_picture_url") val groupPictureUrl: String?,
    @SerializedName("admins_only_chat") val adminsOnlyChat: Boolean,
    @SerializedName("encryption_key") val encryptionKey: String? = null,
    @SerializedName("encryption_iv") val encryptionIv: String? = null,
    @SerializedName("other_user_last_seen") val otherUserLastSeen: String?,
    @SerializedName("other_user_is_online") val otherUserIsOnline: Boolean?,
    @SerializedName("membership_epoch") val membershipEpoch: Int = 1,
    @SerializedName("e2ee_version") val e2eeVersion: Int = 1
) {
    val isCurrentUserAdmin: Boolean
        get() = when (isCurrentUserAdminField) {
            is Boolean -> isCurrentUserAdminField
            is Number -> isCurrentUserAdminField.toInt() == 1
            is String -> isCurrentUserAdminField == "1" || isCurrentUserAdminField.lowercase() == "true"
            else -> false
        }
}

data class Message(
    val id: Int,
    val username: String,
    val message: String,
    val timestamp: String,
    @SerializedName("isEdited") val isEdited: Boolean,
    var status: Int,
    @SerializedName("user_id") val userId: Int,
    @SerializedName("replyToMessageId") val replyToMessageId: Int?,
    @SerializedName("replyToUsername") val replyToUsername: String?,
    @SerializedName("replyToMessageContent") val replyToMessageContent: String?,
    @SerializedName("replyToMessageType") val replyToMessageType: String?,
    @SerializedName("replyToIsViewOnce") val replyToIsViewOnce: Boolean?,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?,
    @SerializedName("message_type") val messageType: String,
    @SerializedName("file_id") val fileId: Int?,
    @SerializedName("file_url") val fileUrl: String?,
    @SerializedName("file_name") val fileName: String?,
    @SerializedName("file_mime_type") val fileMimeType: String?,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("audio_duration") val audioDuration: Int?,
    val reactions: List<Reaction>?,
    @SerializedName("is_view_once") val isViewOnce: Boolean,
    @SerializedName("is_viewed_by_me") val isViewedByMe: Boolean = false,
    val tempId: String? = null
)

data class Reaction(
    @SerializedName("user_id") val userId: Int,
    val username: String,
    @SerializedName("reaction_emoji") val reactionEmoji: String,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?
)

data class NewMessageNotification(
    val id: Int,
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("group_name") val groupName: String,
    @SerializedName("sender_username") val senderUsername: String,
    @SerializedName("encrypted_message") val encryptedMessage: String,
    val timestamp: String,
    @SerializedName("isEdited") val isEdited: Boolean,
    @SerializedName("is_private_chat") val isPrivateChat: Boolean,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?,
    @SerializedName("message_type") val messageType: String,
    @SerializedName("replyToEncryptedContent") val replyToEncryptedContent: String?,
    @SerializedName("replyToUsername") val replyToUsername: String?,
    @SerializedName("is_view_once") val isViewOnce: Boolean,
    @SerializedName("is_mention") val isMention: Boolean
)

data class GetReadReceiptsResponse(
    val status: String,
    val message: String,
    val data: List<ReadReceiptInfo>?
)

data class ReadReceiptInfo(
    val username: String,
    @SerializedName("read_at") val readAt: String
)

// --- SISTEMA E AGGIORNAMENTI ---

data class UpdateInfoResponse(
    val versionCode: Int,
    val versionName: String?,
    val changelog: String,
    val isForced: Boolean,
    val minimumVersionCode: Int,
    @SerializedName("maintenance_mode") val maintenanceMode: Boolean,
    @SerializedName("maintenance_message") val maintenanceMessage: String,
    @SerializedName("isStrict") val isStrict: Boolean? = null
)

data class BetaUpdateResponse(
    @SerializedName("updateId") val updateId: Long,
    @SerializedName("versionCode") val versionCode: Int,
    @SerializedName("versionName") val versionName: String?,
    @SerializedName("changelog") val changelog: String,
    @SerializedName("downloadUrl") val downloadUrl: String,
    @SerializedName("minimumVersionCode") val minimumVersionCode: Int? = null,
    @SerializedName("isForced") val isForced: Boolean? = null,
    @SerializedName("isStrict") val isStrict: Boolean? = null
)

data class BroadcastMessageData(
    val messageId: Long,
    val messageTitle: String,
    val messageText: String,
    val isActive: Boolean
)

// --- CHIAMATE VOCALI (WebRTC) ---

data class CallResponse(
    val status: String,
    val message: String,
    val data: CallData?
)

data class CallData(
    @SerializedName("call_id") val callId: Int
)

data class IncomingCallResponse(
    val status: String,
    val message: String,
    val data: List<IncomingCall>?
)

data class IncomingCall(
    @SerializedName("call_id") val callId: Int,
    @SerializedName("caller_id") val callerId: Int,
    @SerializedName("caller_username") val callerUsername: String,
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("profile_picture_url") val profilePictureUrl: String?
)

data class SignalResponse(
    val status: String,
    val data: List<SignalData>?
)

data class SignalData(
    val id: Int,
    val type: String,
    val payload: String
)

data class WebRtcSignal(
    val type: String,
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
    val candidate: String? = null,
    val isNetworkPoor: Boolean? = null,
    val callStatus: String? = null
)

// --- SFONDI CHAT ---
data class PredefinedImagesResponse(
    val images: List<String>
)

// --- E2EE v2: X3DH & SENDER KEYS ---

data class PreKeyCountData(
    @SerializedName("count") val count: Int
)

data class OneTimePreKeyUpload(
    @SerializedName("opk_id") val opkId: Int,
    @SerializedName("opk_pub") val opkPub: String
)

data class PreKeyBundleData(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("identity_sign_key") val identitySignKey: String,
    @SerializedName("identity_dh_key") val identityDhKey: String,
    @SerializedName("identity_dh_sig") val identityDhSig: String,
    @SerializedName("spk_id") val spkId: Int,
    @SerializedName("spk_pub") val spkPub: String,
    @SerializedName("spk_sig") val spkSig: String,
    @SerializedName("spk_created_at") val spkCreatedAt: Long = 0L,
    @SerializedName("opk_id") val opkId: Int? = null,
    @SerializedName("opk_pub") val opkPub: String? = null
)

data class PeerIdentityKeyData(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("identity_sign_key") val identitySignKey: String,
    @SerializedName("identity_dh_key") val identityDhKey: String,
    @SerializedName("identity_dh_sig") val identityDhSig: String
)

data class KeyDistributionItem(
    @SerializedName("id") val id: Long,
    @SerializedName("group_id") val groupId: Int,
    @SerializedName("sender_id") val senderId: Int,
    @SerializedName("epoch") val epoch: Int = 1,
    @SerializedName("dist_type") val distType: String = "sender_key",
    @SerializedName("payload") val payload: String,
    @SerializedName("created_at") val createdAt: String? = null
)

typealias SendKeyDistributionResponse = BaseResponse