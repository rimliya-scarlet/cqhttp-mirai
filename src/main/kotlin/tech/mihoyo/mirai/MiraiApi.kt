package tech.mihoyo.mirai

import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.LowLevelAPI
import net.mamoe.mirai.contact.PermissionDeniedException
import net.mamoe.mirai.data.GroupAnnouncement
import net.mamoe.mirai.data.GroupAnnouncementMsg
import net.mamoe.mirai.event.events.MemberJoinRequestEvent
import net.mamoe.mirai.event.events.NewFriendRequestEvent
import net.mamoe.mirai.message.data.recall
import tech.mihoyo.mirai.data.common.*
import tech.mihoyo.mirai.web.queue.CacheSourceQueue
import tech.mihoyo.mirai.util.cqMessageToMessageChains
import tech.mihoyo.mirai.util.logger
import tech.mihoyo.mirai.web.queue.CacheRequestQueue


@OptIn(LowLevelAPI::class)
suspend fun callMiraiApi(action: String?, params: Map<String, JsonElement>, mirai: MiraiApi): CQResponseDTO {
    var responseDTO: CQResponseDTO = CQResponseDTO.CQPluginFailure()
    try {
        when (action) {
            "send_msg" -> responseDTO = mirai.cqSendMessage(params)
            "send_private_msg" -> responseDTO = mirai.cqSendPrivateMessage(params)
            "send_group_msg" -> responseDTO = mirai.cqSendGroupMessage(params)
            "send_discuss_msg" -> responseDTO = mirai.cqSendDiscussMessage(params)
            "delete_msg" -> responseDTO = mirai.cqDeleteMessage(params)
            "send_like" -> responseDTO = mirai.cqSendLike(params)
            "set_group_kick" -> responseDTO = mirai.cqSetGroupKick(params)
            "set_group_ban" -> responseDTO = mirai.cqSetGroupBan(params)
            "set_group_anonymous_ban" -> responseDTO = mirai.cqSetAnonymousBan(params)
            "set_group_whole_ban" -> responseDTO = mirai.cqSetWholeGroupBan(params)
            "set_group_admin" -> responseDTO = mirai.cqSetGroupAdmin(params)
            "set_group_anonymous" -> responseDTO = mirai.cqSetGroupAnonymous(params)
            "set_group_card" -> responseDTO = mirai.cqSetGroupCard(params)
            "set_group_leave" -> responseDTO = mirai.cqSetGroupLeave(params)
            "set_group_special_title" -> responseDTO = mirai.cqSetGroupSpecialTitle(params)
            "set_discuss_leave" -> responseDTO = mirai.cqSetDiscussLeave(params)
            "set_friend_add_request" -> responseDTO = mirai.cqSetFriendAddRequest(params)
            "set_group_add_request" -> responseDTO = mirai.cqSetGroupAddRequest(params)
            "get_login_info" -> responseDTO = mirai.cqGetLoginInfo(params)
            "get_stranger_info" -> responseDTO = mirai.cqGetStrangerInfo(params)
            "get_friend_list" -> responseDTO = mirai.cqGetFriendList(params)
            "get_group_list" -> responseDTO = mirai.cqGetGroupList(params)
            "get_group_info" -> responseDTO = mirai.cqGetGroupInfo(params)
            "get_group_member_info" -> responseDTO = mirai.cqGetGroupMemberInfo(params)
            "get_group_member_list" -> responseDTO = mirai.cqGetGroupMemberList(params)
            "get_cookies" -> responseDTO = mirai.cqGetCookies(params)
            "get_csrf_token" -> responseDTO = mirai.cqGetCSRFToken(params)
            "get_credentials" -> responseDTO = mirai.cqGetCredentials(params)
            "get_record" -> responseDTO = mirai.cqGetRecord(params)
            "get_image" -> responseDTO = mirai.cqGetImage(params)
            "can_send_image" -> responseDTO = mirai.cqCanSendImage(params)
            "can_send_record" -> responseDTO = mirai.cqCanSendRecord(params)
            "get_status" -> responseDTO = mirai.cqGetStatus(params)
            "get_version_info" -> responseDTO = mirai.cqGetVersionInfo(params)
            "set_restart_plugin" -> responseDTO = mirai.cqSetRestartPlugin(params)
            "clean_data_dir" -> responseDTO = mirai.cqCleanDataDir(params)
            "clean_plugin_log" -> responseDTO = mirai.cqCleanPluginLog(params)
            ".handle_quick_operation" -> responseDTO = mirai.cqHandleQuickOperation(params)

            "set_group_name" -> responseDTO = mirai.cqSetGroupName(params)

            "_set_group_announcement" -> responseDTO = mirai.cqSetGroupAnnouncement(params)
            else -> {
                logger.error("未知CQHTTP API: $action")
            }
        }
    } catch (e: PermissionDeniedException) {
        logger.debug("机器人无操作权限, 调用的API: /$action")
        responseDTO = CQResponseDTO.CQMiraiFailure()
    } catch (e: Exception) {
        logger.error(e)
        responseDTO = CQResponseDTO.CQPluginFailure()
    }

    return responseDTO
}

@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER")
class MiraiApi(val bot: Bot) {
    // Store temp contact information for sending messages
    // QQ : GroupId
    val cachedTempContact: MutableMap<Long, Long> = mutableMapOf()
    val cacheRequestQueue = CacheRequestQueue()
    private val cachedSourceQueue = CacheSourceQueue()

    suspend fun cqSendMessage(params: Map<String, JsonElement>): CQResponseDTO {
        if (params.contains("message_type")) {
            when (params["message_type"]?.jsonPrimitive?.content) {
                "private" -> return cqSendPrivateMessage(params)
                "group" -> return cqSendGroupMessage(params)
            }
        } else {
            when {
                params["user_id"] != null -> return cqSendPrivateMessage(params)
                params["group_id"] != null -> return cqSendGroupMessage(params)
                params["discuss_id"] != null -> return cqSendGroupMessage(params)
            }
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqSendGroupMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val targetGroupId = params["group_id"]!!.jsonPrimitive.long
        val raw = params["auto_escape"]?.jsonPrimitive?.booleanOrNull ?: false
        val messages = params["message"]

        val group = bot.getGroup(targetGroupId)
        cqMessageToMessageChains(bot, group, messages, raw)?.let {
            val receipt = group.sendMessage(it)
            cachedSourceQueue.add(receipt.source)
            return CQResponseDTO.CQMessageResponse(receipt.source.id)
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqSendPrivateMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val targetQQId = params["user_id"]!!.jsonPrimitive.long
        val raw = params["auto_escape"]?.jsonPrimitive?.booleanOrNull ?: false
        val messages = params["message"]

        val contact = try {
            bot.getFriend(targetQQId)
        } catch (e: NoSuchElementException) {
            val fromGroupId = cachedTempContact[targetQQId]
                ?: bot.groups.find { group -> group.members.contains(targetQQId) }?.id
            bot.getGroup(fromGroupId!!)[targetQQId]
        }

        cqMessageToMessageChains(bot, contact, messages, raw)?.let {
            val receipt = contact.sendMessage(it)
            cachedSourceQueue.add(receipt.source)
            return CQResponseDTO.CQMessageResponse(receipt.source.id)
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqDeleteMessage(params: Map<String, JsonElement>): CQResponseDTO {
        val messageId = params["message_id"]?.jsonPrimitive?.intOrNull
        messageId?.let {
            cachedSourceQueue[it].recall()
            CQResponseDTO.CQGeneralSuccess()
        }
        return CQResponseDTO.CQInvalidRequest()
    }

    suspend fun cqSetGroupKick(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].kick()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupBan(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        val duration = params["duration"]?.jsonPrimitive?.int ?: 30 * 60
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].mute(duration)
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetWholeGroupBan(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val enable = params["enable"]?.jsonPrimitive?.booleanOrNull ?: true
        return if (groupId != null) {
            bot.getGroup(groupId).settings.isMuteAll = enable
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupCard(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        val card = params["card"]?.jsonPrimitive?.contentOrNull ?: ""
        val enable = params["enable"]?.jsonPrimitive?.booleanOrNull ?: true
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].nameCard = card
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupLeave(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val dismiss = params["enable"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (groupId != null) {
            // Not supported
            if (dismiss) return CQResponseDTO.CQMiraiFailure()

            bot.getGroup(groupId).quit()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupSpecialTitle(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        val specialTitle = params["special_title"]?.jsonPrimitive?.contentOrNull ?: ""
        val duration = params["duration"]?.jsonPrimitive?.int ?: -1  // Not supported
        return if (groupId != null && memberId != null) {
            bot.getGroup(groupId)[memberId].specialTitle = specialTitle
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetFriendAddRequest(params: Map<String, JsonElement>): CQResponseDTO {
        val flag = params["flag"]?.jsonPrimitive?.contentOrNull
        val approve = params["approve"]?.jsonPrimitive?.booleanOrNull ?: true
        val remark = params["remark"]?.jsonPrimitive?.contentOrNull
        return if (flag != null) {
            val event = cacheRequestQueue[flag.toLongOrNull()]
            if (event is NewFriendRequestEvent)
                if (approve) event.accept() else event.reject()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    suspend fun cqSetGroupAddRequest(params: Map<String, JsonElement>): CQResponseDTO {
        val flag = params["flag"]?.jsonPrimitive?.contentOrNull
        val type = params["type"]?.jsonPrimitive?.contentOrNull
        val subType = params["sub_type"]?.jsonPrimitive?.contentOrNull
        val approve = params["approve"]?.jsonPrimitive?.booleanOrNull ?: true
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull

        return if (flag != null) {
            val event = cacheRequestQueue[flag.toLongOrNull()]
            if (event is MemberJoinRequestEvent)
                if (approve) event.accept() else event.reject()
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetLoginInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQLoginInfo(bot.id, bot.nick)
    }

    fun cqGetFriendList(params: Map<String, JsonElement>): CQResponseDTO {
        val cqFriendList = mutableListOf<CQFriendData>()
        bot.friends.forEach { friend ->
            cqFriendList.add(CQFriendData(friend.id, friend.nick, ""))
        }
        return CQResponseDTO.CQFriendList(cqFriendList)
    }

    fun cqGetGroupList(params: Map<String, JsonElement>): CQResponseDTO {
        val cqGroupList = mutableListOf<CQGroupData>()
        bot.groups.forEach { group ->
            cqGroupList.add(CQGroupData(group.id, group.name))
        }
        return CQResponseDTO.CQGroupList(cqGroupList)
    }

    /**
     * 获取群信息
     * 不支持获取群容量, 返回0
     */
    fun cqGetGroupInfo(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val noCache = params["no_cache"]?.jsonPrimitive?.long ?: false

        return if (groupId != null) {
            val group = bot.getGroup(groupId)
            CQResponseDTO.CQGroupInfo(group.id, group.name, group.members.size + 1, 0)
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetGroupMemberInfo(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        val noCache = params["no_cache"]?.jsonPrimitive?.long ?: false

        return if (groupId != null && memberId != null) {
            val member = bot.getGroup(groupId)[memberId]
            CQResponseDTO.CQMemberInfo(CQMemberInfoData(member))
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqGetGroupMemberList(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val cqGroupMemberListData = mutableListOf<CQMemberInfoData>()
        return if (groupId != null) {
            var isBotIncluded = false
            val group = bot.getGroup(groupId)
            val members = group.members
            members.forEach { member ->
                run {
                    if (member.id == bot.id) isBotIncluded = true
                    cqGroupMemberListData.add(CQMemberInfoData(member))
                }
            }
            if (!isBotIncluded) cqGroupMemberListData.add(CQMemberInfoData(group.botAsMember))
            CQResponseDTO.CQMemberList(cqGroupMemberListData)
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    // https://github.com/richardchien/coolq-http-api/blob/master/src/cqhttp/plugins/web/http.cpp#L375
    suspend fun cqHandleQuickOperation(params: Map<String, JsonElement>): CQResponseDTO {
        try {
            val context = params["context"]?.jsonObject
            val operation = params["operation"]?.jsonObject
            val postType = context?.get("post_type")?.jsonPrimitive?.content

            if (postType == "message") {
                val messageType = context["message_type"]?.jsonPrimitive?.content

                val replyElement = operation?.get("reply")
                if (replyElement != null) {
                    val nextCallParams = context.toMutableMap()
                    if (messageType == "group" && operation?.get("at_sender")?.jsonPrimitive?.booleanOrNull == true) {
                        context["user_id"]?.jsonPrimitive?.apply {
                            when (replyElement) {
                                is JsonArray -> {
                                    val replyMessageChain = replyElement.jsonArray.toMutableList()
                                    replyMessageChain.add(
                                        0, JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("at"),
                                                "data" to JsonObject(mapOf("qq" to JsonPrimitive(this.long)))
                                            )
                                        )
                                    )
                                    nextCallParams["message"] = JsonArray(replyMessageChain)
                                }
                                is JsonObject -> {
                                    val replyMessageChain = mutableListOf(replyElement.jsonObject)
                                    replyMessageChain.add(
                                        0, JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("at"),
                                                "data" to JsonObject(mapOf("qq" to JsonPrimitive(this.long)))
                                            )
                                        )
                                    )
                                    nextCallParams["message"] = JsonArray(replyMessageChain)
                                }
                                else -> {
                                    val textToReply = "[CQ:at,qq=$this] ${replyElement.jsonPrimitive.content}"
                                    nextCallParams["message"] = JsonPrimitive(textToReply)
                                }
                            }
                        }
                    }
                    return cqSendMessage(nextCallParams)
                }

                if (messageType == "group") {
                    // TODO: 备忘, 暂未支持
                    val isAnonymous = false
                    if (operation?.get("delete")?.jsonPrimitive?.booleanOrNull == true) {
                        return cqDeleteMessage(context)
                    }
                    if (operation?.get("kick")?.jsonPrimitive?.booleanOrNull == true) {
                        return cqSetGroupKick(context)
                    }
                    if (operation?.get("ban")?.jsonPrimitive?.booleanOrNull == true) {
                        @Suppress("ConstantConditionIf")
                        (return if (isAnonymous) {
                            cqSetAnonymousBan(context)
                        } else {
                            cqSetGroupBan(context)
                        })
                    }
                }
            } else if (postType == "request") {
                val requestType = context["request_type"]?.jsonPrimitive?.content
                val approveOpt = operation?.get("approve")?.jsonPrimitive?.booleanOrNull ?: false
                val nextCallParams = context.toMutableMap()
                nextCallParams["approve"] = JsonPrimitive(approveOpt)
                nextCallParams["remark"] = JsonPrimitive(operation?.get("remark")?.jsonPrimitive?.contentOrNull)
                nextCallParams["reason"] = JsonPrimitive(operation?.get("reason")?.jsonPrimitive?.contentOrNull)
                if (requestType == "friend") {
                    return cqSetFriendAddRequest(nextCallParams)
                } else if (requestType == "group") {
                    return cqSetGroupAddRequest(nextCallParams)
                }
            }
            return CQResponseDTO.CQInvalidRequest()
        } catch (e: Exception) {
            return CQResponseDTO.CQPluginFailure()
        }
    }

    /**
     * Getting image path, not supported for now
     */
    fun cqGetImage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQPluginFailure()
    }

    fun cqCanSendImage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQCanSendImage()
    }

    fun cqCanSendRecord(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQCanSendRecord()
    }

    fun cqGetStatus(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQPluginStatus(CQPluginStatusData(online = bot.isActive, good = bot.isActive))
    }

    fun cqGetVersionInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQVersionInfo(
            CQVersionInfoData(
                coolq_directory = PluginBase.dataFolder.absolutePath,
                plugin_version = "",
                plugin_build_number = ""
            )
        )
    }

    fun cqSetRestartPlugin(params: Map<String, JsonElement>): CQResponseDTO {
        val delay = params["delay"]?.jsonPrimitive?.int ?: 0
        return CQResponseDTO.CQGeneralSuccess()
    }

    fun cqCleanDataDir(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQGeneralSuccess()
    }

    fun cqCleanPluginLog(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQGeneralSuccess()
    }

    ////////////////
    ////  v11  ////
    //////////////

    fun cqSetGroupName(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val name = params["name"]?.jsonPrimitive?.content

        return if (groupId != null && name != null && name != "") {
            bot.getGroup(groupId).name = name
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    /////////////////
    //// hidden ////
    ///////////////

    @LowLevelAPI
    suspend fun cqSetGroupAnnouncement(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val content = params["content"]?.jsonPrimitive?.content

        return if (groupId != null && content != null && content != "") {
            bot._lowLevelSendAnnouncement(groupId, GroupAnnouncement(msg = GroupAnnouncementMsg(text = content)))
            CQResponseDTO.CQGeneralSuccess()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    ////////////////////////////////
    //// currently unsupported ////
    //////////////////////////////

    fun cqGetCookies(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetCSRFToken(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetRecord(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetCredentials(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqGetStrangerInfo(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSendDiscussMessage(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSetGroupAnonymous(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val enable = params["enable"]?.jsonPrimitive?.long ?: true
        return if (groupId != null) {
            // Not supported
            // bot.getGroup(groupId).settings.isAnonymousChatEnabled = enable
            CQResponseDTO.CQMiraiFailure()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetGroupAdmin(params: Map<String, JsonElement>): CQResponseDTO {
        val groupId = params["group_id"]?.jsonPrimitive?.long
        val memberId = params["user_id"]?.jsonPrimitive?.long
        val enable = params["enable"]?.jsonPrimitive?.long ?: true
        return if (groupId != null && memberId != null) {
            // Not supported
            // bot.getGroup(groupId)[memberId].permission = if (enable) MemberPermission.ADMINISTRATOR else MemberPermission.MEMBER
            CQResponseDTO.CQMiraiFailure()
        } else {
            CQResponseDTO.CQInvalidRequest()
        }
    }

    fun cqSetDiscussLeave(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSendLike(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }

    fun cqSetAnonymousBan(params: Map<String, JsonElement>): CQResponseDTO {
        return CQResponseDTO.CQMiraiFailure()
    }
}