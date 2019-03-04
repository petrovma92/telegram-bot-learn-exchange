package root.bot

import org.apache.log4j.Logger
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.ForwardMessage
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import root.data.MainAdmin
import root.data.Text
import root.data.UserData
import root.data.UserState
import root.service.AdminService
import java.time.OffsetDateTime.now
import java.util.ArrayList
import java.util.HashMap

import root.data.UserState.*
import root.data.entity.*
import root.libs.*

class TelegramBot : TelegramLongPollingBot {

    private val userStates: HashMap<Int, UserData> = HashMap()

    companion object {
        private val log = Logger.getLogger(TelegramBot::class.java)!!
    }

    private val botUsername: String
    private val botToken: String
    private val tasks: Map<String, String>
    private val text: Text
    private val mainAdmins: List<MainAdmin>
    private val service: AdminService


    constructor(
        botUsername: String,
        botToken: String,
        tasks: Map<String, String>,
        text: Text,
        service: AdminService,
        mainAdmins: List<MainAdmin>,
        options: DefaultBotOptions?
    ) : super(options) {
        this.botUsername = botUsername
        this.botToken = botToken
        this.tasks = tasks
        this.text = text
        this.service = service
        this.mainAdmins = mainAdmins
    }

    constructor(
        botUsername: String,
        botToken: String,
        tasks: Map<String, String>,
        text: Text,
        service: AdminService,
        mainAdmins: List<MainAdmin>
    ) : super() {
        this.botUsername = botUsername
        this.botToken = botToken
        this.tasks = tasks
        this.text = text
        this.service = service
        this.mainAdmins = mainAdmins
    }

    override fun onUpdateReceived(update: Update) {
        log.info(
            "\nMessage: " + update.message?.text +
                    "\nFromMsg: " + update.message?.from +
                    "\nChat: " + update.message?.chat +
                    "\nCallbackQuery: " + update.callbackQuery?.data +
                    "\nFromCallBck: " + update.callbackQuery?.from +
                    "\nChatId: " + update.message?.chatId
        )
        if (update.hasCallbackQuery()) {
            val sender = update.callbackQuery.from
            try {
//            todo if (update.message.isGroupMessage || update.message.isChannelMessage || update.message.isSuperGroupMessage) {
                when {
                    mainAdmins.contains(MainAdmin(sender.id, sender.userName)) -> doMainAdminCallback(update)
                    else -> service.getSuperAdminById(sender.id)?.let { doSuperAdminCallback(update) }
                        ?: service.getAdminById(sender.id)?.let { doAdminCallback(update) } ?: doUserCallback(update)
                }

            } catch (t: Throwable) {
                t.printStackTrace()
                log.error("error when try response to callback: ${update.callbackQuery.data}", t)
                execute(AnswerCallbackQuery().also {
                    it.callbackQueryId = update.callbackQuery.id
                    it.text = text.errCallback
                })
                userStates.remove(sender.id)
            }
        } else if (update.message.isUserMessage) {
            val sender = update.message.from
            try {
//            todo if (update.message.isGroupMessage || update.message.isChannelMessage || update.message.isSuperGroupMessage) {
                when {
                    mainAdmins.contains(MainAdmin(sender.id, sender.userName)) -> doMainAdminUpdate(update)
                    else -> service.getSuperAdminById(sender.id)?.let { doSuperAdminUpdate(update, it) }
                        ?: service.getAdminById(sender.id)?.let { doAdminUpdate(update, it) } ?: doUserUpdate(update)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                log.error("error when try response to message: ${update.message.text}", t)
                userStates.remove(sender.id)
            }
        }
    }

    private fun doMainAdminUpdate(upd: Update) {
        when (userStates[upd.message.from.id]?.state) {
            CREATE_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val name = upd.message.text

                        service.createCampaign(
                            Campaign(
                                name = name,
                                createDate = now(),
                                groups = emptySet(),
                                surveys = emptySet()
                            )
                        )

                        end(upd, text.sucCreateCampaign) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errCreateCampaign, upd.message.chatId)
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            ADD_ADMIN_TO_CAMPAIGN -> {

                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val ids = upd.message.text.split("\\s+".toRegex(), 2)
                        val adminId = ids[0].toInt()
                        val name = ids[1]

                        service.getCampaignByName(name)?.let { camp ->
                            service.getAdminById(adminId)?.let { admin ->
                                admin.campaigns =
                                    admin.campaigns.toHashSet().also { gr -> gr.add(camp) }
                                service.saveAdmin(admin)
                            } ?: service.saveAdmin(
                                Admin(
                                    userId = adminId,
                                    createDate = now(),
                                    campaigns = setOf(camp)
                                )
                            )

                            end(upd, text.sucAdminToCampaign) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        } ?: {
                            sendMessage(text.errCampaignNotFound, upd.message.chatId)
                        }.invoke()

                    } catch (t: Throwable) {
                        sendMessage(text.errAdminToCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            ADD_SUPER_ADMIN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val adminId = upd.message.text.toInt()

                        service.getSuperAdminById(adminId)?.let {
                            end(upd, text.errAddSuperAdminAlreadyExist) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        } ?: {
                            service.saveSuperAdmin(SuperAdmin(userId = adminId, createDate = now()))
                            end(upd, text.sucAddSuperAdmin) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        }.invoke()

                    } catch (t: Throwable) {
                        sendMessage(text.errAddSuperAdmin, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            REMOVE_SUPER_ADMIN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        service.deleteSuperAdminById(upd.message.text.toInt())
                        end(upd, text.sucRemoveSuperAdmin) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveSuperAdmin, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            ADD_GROUP_TO_CAMPAIGN -> {

                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val ids = upd.message.text.split("\\s+".toRegex(), 2)
                        val groupId = ids[0].toLong()
                        val name = ids[1]

                        service.getCampaignByName(name)?.let {
                            val group = service.createGroup(Group(groupId, now()))
                            it.groups = it.groups.toHashSet().also { gr -> gr.add(group) }
                            service.updateCampaign(it)
                        } ?: {
                            sendMessage(text.errCampaignNotFound, upd.message.chatId)
                        }.invoke()

                        end(upd, text.sucGroupToCampaign) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errGroupToCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            REMOVE_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        service.deleteCampaignByName(upd.message.text)
                        end(upd, text.sucRemoveCampaign) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveCampaign, upd.message.chatId)
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            REMOVE_ADMIN_FROM_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val params = upd.message.text.split("\\s+".toRegex(), 2)
                        val adminForDelete = service.getAdminById(params[0].toInt())
                        adminForDelete!!.campaigns =
                            adminForDelete.campaigns.filter { it.name != params[1] }.toHashSet()

                        service.saveAdmin(adminForDelete)
                        end(upd, text.sucRemoveAdminFromCampaign) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveAdminFromCampaign, upd.message.chatId)
                        log.error("AdminGroup deleting err.", t)
                    }
                }
            }
            REMOVE_GROUP_FROM_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val params = upd.message.text.split("\\s+".toRegex(), 2)
                        val groupId = params[0].toLong()
                        val campaign = service.getCampaignByName(params[1])
                        campaign!!.groups = campaign.groups.filter { it.groupId != groupId }.toHashSet()

                        service.updateCampaign(campaign)
                        end(upd, text.sucRemoveGroupFromCampaign) { msg: Message, text: String ->
                            mainAdminMenu(msg, text)
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveGroupFromCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            SURVEY_NAME -> {
                val survey = Survey(name = upd.message.text, createDate = now(), questions = emptySet())
                userStates[upd.message.from.id] =
                    UserData(SURVEY_ACTIONS, upd.message.from, survey = survey)
                sendSurveyMsg(survey, upd.message.chatId)
            }
            MSG_TO_USERS -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val users = userStates[upd.message.from.id]?.users
                        if (users?.firstOrNull() != null) {
                            msgToUsers(users, upd)
                            end(upd, text.sucMsgToUsers) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        } else
                            end(upd, text.errMsgToUsersNotFound) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToUsers, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            MSG_TO_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> try {
                        val groups = userStates[upd.message.from.id]?.groups
                        if (!groups.isNullOrEmpty()) {
                            msgToCampaign(groups.toList(), upd)
                            end(upd, text.sucMsgToCampaign) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        } else {
                            end(upd, text.errMsgToCampaignNotFound) { msg: Message, text: String ->
                                mainAdminMenu(msg, text)
                            }
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToCampaign, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            else -> {
                when (upd.message.text) {
                    text.removeCampaign -> {
                        resetMenu(upd.message, text.msgRemoveCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_CAMPAIGN, upd.message.from)
                    }
                    text.removeAdminFromCampaign -> {
                        resetMenu(upd.message, text.msgRemoveAdminFromCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_ADMIN_FROM_CAMPAIGN, upd.message.from)
                    }
                    text.removeGroupFromCampaign -> {
                        resetMenu(upd.message, text.msgRemoveGroupFromCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_GROUP_FROM_CAMPAIGN, upd.message.from)
                    }
                    text.addAdminToCampaign -> {
                        resetMenu(upd.message, text.msgAdminToCampaign)
                        userStates[upd.message.from.id] =
                            UserData(ADD_ADMIN_TO_CAMPAIGN, upd.message.from)
                    }
                    text.addGroupToCampaign -> {
                        resetMenu(upd.message, text.msgGroupToCampaign)
                        userStates[upd.message.from.id] =
                            UserData(ADD_GROUP_TO_CAMPAIGN, upd.message.from)
                    }
                    text.addSuperAdmin -> {
                        resetMenu(upd.message, text.msgAddSuperAdmin)
                        userStates[upd.message.from.id] =
                            UserData(ADD_SUPER_ADMIN, upd.message.from)
                    }
                    text.removeSuperAdmin -> {
                        resetMenu(upd.message, text.msgAddSuperAdmin)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_SUPER_ADMIN, upd.message.from)
                    }
                    text.createCampaign -> {
                        resetMenu(upd.message, text.msgCreateCampaign)
                        userStates[upd.message.from.id] =
                            UserData(CREATE_CAMPAIGN, upd.message.from)
                    }
                    text.sendToEveryUser -> {
                        resetMenu(upd.message, text.msgSendToEveryUser)

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_USERS_MSG.toString(),
                                upd.message.chatId,
                                availableCampaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_USERS_MSG, upd.message.from)
                        }
                    }
                    text.sendToEveryGroup -> {
                        resetMenu(upd.message, text.msgSendToEveryGroup)

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                upd.message.chatId,
                                availableCampaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, upd.message.from)
                        }
                    }
                    text.survey -> {
                        resetMenu(upd.message, text.msgSurvey)

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaignsSurveys,
                                CAMPAIGN_FOR_SURVEY.toString(),
                                upd.message.chatId,
                                availableCampaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SURVEY, upd.message.from)
                        }
                    }
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        mainAdminMenu(upd.message)
                    }
                    else -> {
                        mainAdminMenu(upd.message)
                    }
                }
            }
        }
    }

    private fun doSuperAdminUpdate(upd: Update, admin: SuperAdmin) {
        if (!admin.equals(upd.message.from)) {
            admin.update(upd.message.from)
            service.saveSuperAdmin(admin)
        }
        when (userStates[upd.message.from.id]?.state) {
            CREATE_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val name = upd.message.text

                        service.createCampaign(
                            Campaign(
                                name = name,
                                createDate = now(),
                                groups = emptySet(),
                                surveys = emptySet()
                            )
                        )

                        end(upd, text.sucCreateCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errCreateCampaign, upd.message.chatId)
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            ADD_ADMIN_TO_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val ids = upd.message.text.split("\\s+".toRegex(), 2)
                        val adminId = ids[0].toInt()
                        val name = ids[1]

                        service.getCampaignByName(name)?.let { camp ->
                            service.getAdminById(adminId)?.let { admin ->
                                admin.campaigns =
                                    admin.campaigns.toHashSet().also { gr -> gr.add(camp) }
                                service.saveAdmin(admin)
                            } ?: service.saveAdmin(
                                Admin(
                                    userId = adminId,
                                    createDate = now(),
                                    campaigns = setOf(camp)
                                )
                            )

                            end(upd, text.sucAdminToCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                        } ?: {
                            sendMessage(text.errCampaignNotFound, upd.message.chatId)
                        }.invoke()

                    } catch (t: Throwable) {
                        sendMessage(text.errAdminToCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            ADD_GROUP_TO_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val ids = upd.message.text.split("\\s+".toRegex(), 2)
                        val groupId = ids[0].toLong()
                        val name = ids[1]

                        service.getCampaignByName(name)?.let {
                            val group = service.createGroup(Group(groupId, now()))
                            it.groups = it.groups.toHashSet().also { gr -> gr.add(group) }
                            service.updateCampaign(it)
                        } ?: {
                            sendMessage(text.errCampaignNotFound, upd.message.chatId)
                        }.invoke()

                        end(upd, text.sucGroupToCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errGroupToCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            REMOVE_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        service.deleteCampaignByName(upd.message.text)
                        end(upd, text.sucRemoveCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveCampaign, upd.message.chatId)
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            REMOVE_ADMIN_FROM_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val params = upd.message.text.split("\\s+".toRegex(), 2)
                        val adminForDelete = service.getAdminById(params[0].toInt())
                        adminForDelete!!.campaigns =
                            adminForDelete.campaigns.filter { it.name != params[1] }.toHashSet()

                        service.saveAdmin(adminForDelete)
                        end(upd, text.sucRemoveAdminFromCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveAdminFromCampaign, upd.message.chatId)
                        log.error("AdminGroup deleting err.", t)
                    }
                }
            }
            REMOVE_GROUP_FROM_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val params = upd.message.text.split("\\s+".toRegex(), 2)
                        val groupId = params[0].toLong()
                        val campaign = service.getCampaignByName(params[1])
                        campaign!!.groups = campaign.groups.filter { it.groupId != groupId }.toHashSet()

                        service.updateCampaign(campaign)
                        end(upd, text.sucRemoveGroupFromCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveGroupFromCampaign, upd.message.chatId)
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            MSG_TO_USERS -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val users = userStates[upd.message.from.id]?.users
                        if (users?.firstOrNull() != null) {
                            msgToUsers(users, upd)
                            end(upd, text.sucMsgToUsers) { msg: Message, _: String ->
                                superAdminMenu(msg)
                            }
                        } else
                            end(upd, text.errMsgToUsersNotFound) { msg: Message, _: String ->
                                superAdminMenu(msg)
                            }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToUsers, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            MSG_TO_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> try {
                        val groups = userStates[upd.message.from.id]?.groups
                        if (!groups.isNullOrEmpty()) {
                            msgToCampaign(groups.toList(), upd)
                            end(upd, text.sucMsgToCampaign) { msg: Message, _: String ->
                                superAdminMenu(msg)
                            }
                        } else {
                            end(upd, text.errMsgToCampaignNotFound) { msg: Message, _: String ->
                                superAdminMenu(msg)
                            }
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToCampaign, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            else -> {
                when (upd.message.text) {
                    text.removeCampaign -> {
                        resetMenu(upd.message, text.msgRemoveCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_CAMPAIGN, upd.message.from)
                    }
                    text.removeAdminFromCampaign -> {
                        resetMenu(upd.message, text.msgRemoveAdminFromCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_ADMIN_FROM_CAMPAIGN, upd.message.from)
                    }
                    text.removeGroupFromCampaign -> {
                        resetMenu(upd.message, text.msgRemoveGroupFromCampaign)
                        userStates[upd.message.from.id] =
                            UserData(REMOVE_GROUP_FROM_CAMPAIGN, upd.message.from)
                    }
                    text.addAdminToCampaign -> {
                        resetMenu(upd.message, text.msgAdminToCampaign)
                        userStates[upd.message.from.id] =
                            UserData(ADD_ADMIN_TO_CAMPAIGN, upd.message.from)
                    }
                    text.addGroupToCampaign -> {
                        resetMenu(upd.message, text.msgGroupToCampaign)
                        userStates[upd.message.from.id] =
                            UserData(ADD_GROUP_TO_CAMPAIGN, upd.message.from)
                    }
                    text.createCampaign -> {
                        resetMenu(upd.message, text.msgCreateCampaign)
                        userStates[upd.message.from.id] =
                            UserData(CREATE_CAMPAIGN, upd.message.from)
                    }
                    text.sendToEveryUser -> {
                        resetMenu(upd.message, text.msgSendToEveryUser)

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_USERS_MSG.toString(),
                                upd.message.chatId,
                                availableCampaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_USERS_MSG, upd.message.from)
                        }
                    }
                    text.sendToEveryGroup -> {
                        resetMenu(upd.message, text.msgSendToEveryGroup)

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                upd.message.chatId,
                                availableCampaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, upd.message.from)
                        }
                    }
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        superAdminMenu(upd.message)
                    }
                    else -> {
                        superAdminMenu(upd.message)
                    }
                }
            }
        }
    }

    private fun doAdminUpdate(upd: Update, admin: Admin) {
        if (!admin.equals(upd.message.from)) {
            admin.update(upd.message.from)
            service.saveAdmin(admin)
        }
        when (userStates[admin.userId]?.state) {
            MSG_TO_USERS -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        adminMenu(upd.message)
                    }
                    else -> try {
                        val users = userStates[upd.message.from.id]?.users
                        if (users?.firstOrNull() != null) {
                            msgToUsers(users, upd)
                            end(upd, text.sucMsgToUsers) { msg: Message, _: String -> adminMenu(msg) }
                        } else
                            end(upd, text.errMsgToUsersNotFound) { msg: Message, _: String -> adminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToUsers, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            MSG_TO_CAMPAIGN -> {
                when (upd.message.text) {
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        adminMenu(upd.message)
                    }
                    else -> try {
                        val groups = userStates[upd.message.from.id]?.groups
                        if (!groups.isNullOrEmpty()) {
                            msgToCampaign(groups.toList(), upd)
                            end(upd, text.sucMsgToCampaign) { msg: Message, _: String -> adminMenu(msg) }
                        } else {
                            end(upd, text.errMsgToCampaignNotFound) { msg: Message, _: String -> adminMenu(msg) }
                        }
                    } catch (t: Throwable) {
                        sendMessage(text.errMsgToCampaign, upd.message.chatId)
                        log.error("error msgToUsers", t)
                    }
                }
            }
            else -> {
                when (upd.message.text) {
                    text.sendToEveryUser -> {
                        resetMenu(upd.message, text.msgSendToEveryUser)

                        if (admin.campaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_USERS_MSG.toString(),
                                upd.message.chatId,
                                admin.campaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_USERS_MSG, upd.message.from)
                        }
                    }
                    text.sendToEveryGroup -> {
                        resetMenu(upd.message, text.msgSendToEveryGroup)

                        if (admin.campaigns.isNotEmpty()) {
                            sendAvailableCampaignsList(
                                text.adminAvailableCampaigns,
                                CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                upd.message.chatId,
                                admin.campaigns
                            )
                            userStates[upd.message.from.id] =
                                UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, upd.message.from)
                        }
                    }
                    text.reset -> {
                        userStates.remove(upd.message.from.id)
                        adminMenu(upd.message)
                    }
                    else -> {
                        adminMenu(upd.message)
                    }
                }
            }
        }
    }

    private fun doUserUpdate(upd: Update) {
        when (userStates[upd.message.from.id]?.state) {
            JOIN_TO_CAMPAIGN -> {
                val userChats = getAllUserChats(
                    service.getAllGroups().toList(),
                    upd.message.from.id
                )
            }
            USER_MENU -> {

            }
            else -> {
                when (upd.message.text) {
                    text.joinToCampaign -> {
                        val userChats = getAllUserChats(
                            service.getAllGroups().toList(),
                            upd.message.from.id
                        )

                        if (userChats.isNotEmpty()) {
                            val availableCampaigns = service.getAllCampaignsByChatListNotContainsUser(
                                userChats.map { it.groupId },
                                upd.message.from.id
                            ).toList()

                            if (availableCampaigns.isNotEmpty()) {
                                sendAvailableCampaignsList(
                                    text.userAvailableCampaigns,
                                    USER_CAMPAIGN_MENU.toString(),
                                    upd.message.chatId,
                                    availableCampaigns
                                )
                                userStates[upd.message.from.id] =
                                    UserData(USER_CAMPAIGN_MENU, upd.message.from)
                            } else
                                sendMessage(
                                    text.msgUserAvailableCampaignsNotFound,
                                    upd.message.chatId
                                )
                        } else {
                            sendMessage(text.inviteText, upd.message.chatId)
                        }
                    }
                    text.showUserCampaigns -> {

                    }
                    else -> {
                        userMenu(upd.message)
                    }
                }
            }
        }
    }

    private fun doMainAdminCallback(upd: Update) {
        val params = upd.callbackQuery.data.split("\\s+".toRegex(), 2)
        val callbackAnswer = AnswerCallbackQuery().also { it.callbackQueryId = upd.callbackQuery.id }
        val callBackCommand: UserState

        try {
            callBackCommand = UserState.valueOf(params[0])
        } catch (e: Exception) {
            log.error("UserState = \"${upd.callbackQuery.data}\", not found", e)
            execute(callbackAnswer.also { it.text = text.errClbCommon })
            throw e
        }

        when {
            SURVEY_CREATE == callBackCommand -> {
                TODO("Survey create")
            }
            SURVEY_DELETE == callBackCommand -> {
                TODO("Survey delete")
            }
            SURVEY_NAME == callBackCommand -> {
                TODO("Survey name")
            }
            SURVEY_SAVE == callBackCommand -> {
                mainAdminMenu(upd.callbackQuery.message)
                TODO("Save survey")
            }
            SURVEY_EDIT == callBackCommand -> {
                //todo TEST SURVEY
                userStates[upd.callbackQuery.from.id]!!.survey = Survey(
                    name = "test srv",
                    description = "test description  test description  test description  test description  test description  test description  test description  test description",
                    questions = setOf(
                        Question(
                            text = "Question_1", sortPoints = 2,
                            options = setOf(
                                Option(text = "Option_1", value = 11, sortPoints = 1),
                                Option(text = "Option_2", value = 12, sortPoints = 2),
                                Option(text = "Option_3", value = 13, sortPoints = 3),
                                Option(text = "Option_4", value = 14, sortPoints = 4),
                                Option(text = "Option_5", value = 15, sortPoints = 5)
                            )
                        ),
                        Question(
                            text = "Question_2", sortPoints = 23,
                            options = setOf(
                                Option(text = "Option_21", value = 21, sortPoints = 1),
                                Option(text = "Option_22", value = 22, sortPoints = 2),
                                Option(text = "Option_23", value = 23, sortPoints = 3),
                                Option(text = "Option_24", value = 24, sortPoints = 4),
                                Option(text = "Option_25", value = 25, sortPoints = 5)
                            )
                        ),
                        Question(
                            text = "Question_3", sortPoints = 42,
                            options = setOf(
                                Option(text = "Option_31", value = 31, sortPoints = 1),
                                Option(text = "Option_32", value = 32, sortPoints = 2),
                                Option(text = "Option_33", value = 33, sortPoints = 3),
                                Option(text = "Option_34", value = 34, sortPoints = 4),
                                Option(text = "Option_35", value = 35, sortPoints = 5)
                            )
                        ),
                        Question(
                            text = "Question_4", sortPoints = 26,
                            options = setOf(
                                Option(text = "Option_41", value = 411, sortPoints = 1),
                                Option(text = "Option_42", value = 412, sortPoints = 2),
                                Option(text = "Option_43", value = 413, sortPoints = 3),
                                Option(text = "Option_44", value = 414, sortPoints = 4),
                                Option(text = "Option_45", value = 415, sortPoints = 5)
                            )
                        ),
                        Question(
                            text = "Question_5", sortPoints = 92,
                            options = setOf(
                                Option(text = "Option_51", value = 511, sortPoints = 15),
                                Option(text = "Option_52", value = 512, sortPoints = 25),
                                Option(text = "Option_53", value = 513, sortPoints = 35),
                                Option(text = "Option_54", value = 514, sortPoints = 45),
                                Option(text = "Option_55", value = 515, sortPoints = 55)
                            )
                        )
                    ),
                    createDate = now()
                )

                showSurvey(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbEditSurvey })
            }
            SURVEY_BACK == callBackCommand -> {
                end(upd) { msg: Message, text: String -> mainAdminMenu(msg, text) }
                deleteMessage(upd.callbackQuery.message)
            }
            SURVEY_QUESTION_SELECT_BACK == callBackCommand -> {
                showSurvey(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurvey })
            }
            SURVEY_OPTION_SELECT_BACK == callBackCommand -> {
                editQuestion(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurvey })
            }
            SURVEY_QUESTIONS == callBackCommand -> {
                showQuestions(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyQuestions })
            }
            SURVEY_QUESTION_BACK == callBackCommand -> {
                showQuestions(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyQuestions })
            }
            SURVEY_QUESTION_SELECT == callBackCommand -> {
                userStates[upd.callbackQuery.from.id]!!.question =
                    userStates[upd.callbackQuery.from.id]!!.survey!!.questions.first { it.text == params[1] }
                editQuestion(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyQuestionEdit })
            }
            SURVEY_OPTION_BACK == callBackCommand -> {
                showOptions(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyOptions })
            }
            SURVEY_OPTION_SELECT == callBackCommand -> {
                userStates[upd.callbackQuery.from.id]!!.option =
                    userStates[upd.callbackQuery.from.id]!!.question!!.options.first { it.text == params[1] }
                showOptions(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyOptions })
            }
            CAMPAIGN_FOR_SEND_GROUP_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_GROUP_MSG -> try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()
                val groups = campaign.groups

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_CAMPAIGN, upd.callbackQuery.from, groups = groups)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryGroup })
                setTextToMessage(
                    resourceText(text.sucSendMessageToEveryGroup, "campaign.name" to campaign.name),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_GROUP_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryGroup })
                throw t
            }
            CAMPAIGN_FOR_SEND_USERS_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_USERS_MSG -> try {

                val users = service.getUsersByCampaignId(params[1].toLong())

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_USERS, upd.callbackQuery.from, users = users)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryUsers })
                setTextToMessage(
                    resourceText("${text.sucSendMessageToEveryUsers} id = ", "campaign.name" to params[1]),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_USERS_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            CAMPAIGN_FOR_SURVEY == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SURVEY -> try {
                when (params[1]) {
                    "create" -> {
                        surveyCreate(upd, params) { msg: Message, text: String -> mainAdminMenu(msg, text) }
                    }
                    "delete" -> {
                        surveyDelete(upd, params) { msg: Message, text: String -> mainAdminMenu(msg, text) }
                    }
                    else -> {
                        val campaign = service.getCampaignById(params[1].toLong())
                            ?: throw CampaignNotFoundException()

                        userStates[upd.callbackQuery.from.id] =
                            UserData(CAMPAIGN_FOR_SURVEY, upd.callbackQuery.from, campaign = campaign)
                        editMessage(
                            EditMessageText().also { editMessage ->
                                editMessage.chatId = upd.callbackQuery.message.chatId.toString()
                                editMessage.messageId = upd.callbackQuery.message.messageId
                                editMessage.text = text.surveyOptions
                                editMessage.replyMarkup = InlineKeyboardMarkup().also { markup ->
                                    markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                                        keyboard.addElements(
                                            listOf(
                                                InlineKeyboardButton().setText(text.surveyOptionCreate)
                                                    .setCallbackData("$CAMPAIGN_FOR_SURVEY create")
                                            ),
                                            listOf(
                                                InlineKeyboardButton().setText(text.editSurvey)
                                                    .setCallbackData("$SURVEY_EDIT")
                                            ),
                                            listOf(
                                                InlineKeyboardButton().setText(text.surveyOptionDelete)
                                                    .setCallbackData("$CAMPAIGN_FOR_SURVEY delete")
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SURVEY execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            else -> surveyActions(callBackCommand, upd, callbackAnswer)
        }
    }

    private fun doSuperAdminCallback(upd: Update) {
        val params = upd.callbackQuery.data.split("\\s+".toRegex(), 2)
        val callbackAnswer = AnswerCallbackQuery().also { it.callbackQueryId = upd.callbackQuery.id }
        val callBackCommand: UserState

        try {
            callBackCommand = UserState.valueOf(params[0])
        } catch (e: Exception) {
            log.error("UserState = \"${upd.callbackQuery.data}\", not found", e)
            execute(callbackAnswer.also { it.text = text.errClbCommon })
            throw e
        }

        when {
            CAMPAIGN_FOR_SEND_GROUP_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_GROUP_MSG -> try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()
                val groups = campaign.groups

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_CAMPAIGN, upd.callbackQuery.from, groups = groups)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryGroup })
                setTextToMessage(
                    resourceText(text.sucSendMessageToEveryGroup, "campaign.name" to campaign.name),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_GROUP_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryGroup })
                throw t
            }
            CAMPAIGN_FOR_SEND_USERS_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_USERS_MSG -> try {

                val users = service.getUsersByCampaignId(params[1].toLong())

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_USERS, upd.callbackQuery.from, users = users)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryUsers })
                setTextToMessage(
                    resourceText("${text.sucSendMessageToEveryUsers} id = ", "campaign.name" to params[1]),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_USERS_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            else -> {
                setTextToMessage(
                    resourceText(text.errCommon),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.errClbCommon })
            }
        }
    }

    private fun doAdminCallback(upd: Update) {
        val params = upd.callbackQuery.data.split("\\s+".toRegex(), 2)
        val callbackAnswer = AnswerCallbackQuery().also { it.callbackQueryId = upd.callbackQuery.id }
        val callBackCommand: UserState

        try {
            callBackCommand = UserState.valueOf(params[0])
        } catch (e: Exception) {
            log.error("UserState = \"${upd.callbackQuery.data}\", not found", e)
            execute(callbackAnswer.also { it.text = text.errClbCommon })
            throw e
        }

        when {
            CAMPAIGN_FOR_SEND_GROUP_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_GROUP_MSG -> try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()
                val groups = campaign.groups

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_CAMPAIGN, upd.callbackQuery.from, groups = groups)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryGroup })
                setTextToMessage(
                    resourceText(text.sucSendMessageToEveryGroup, "campaign.name" to campaign.name),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_GROUP_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryGroup })
                throw t
            }
            CAMPAIGN_FOR_SEND_USERS_MSG == callBackCommand &&
                    userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_USERS_MSG -> try {

                val users = service.getUsersByCampaignId(params[1].toLong())

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_USERS, upd.callbackQuery.from, users = users)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryUsers })
                setTextToMessage(
                    resourceText("${text.sucSendMessageToEveryUsers} id = ", "campaign.name" to params[1]),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_USERS_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            else -> {
                setTextToMessage(
                    resourceText(text.errCommon),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.errClbCommon })
            }
        }
    }

    private fun doUserCallback(upd: Update) {
        val params = upd.callbackQuery.data.split("\\s+".toRegex(), 2)
        val callbackAnswer = AnswerCallbackQuery()
        val callBackCommand: UserState
        callbackAnswer.callbackQueryId = upd.callbackQuery.id

        try {
            callBackCommand = UserState.valueOf(params[0])
        } catch (e: Exception) {
            log.error("UserState = \"${upd.callbackQuery.data}\", not found", e)
            execute(callbackAnswer.also { it.text = text.errClbUserAddedToCampaign })
            throw e
        }

        val campaignForAdd = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()

        when (callBackCommand) {
            USER_CAMPAIGN_MENU -> {
                service.getUserById(upd.callbackQuery.from.id)?.let {
                    service.createOrUpdateGroupUser(
                        UserInGroup(
                            upd.callbackQuery.from.id,
                            createDate = now(),
                            firstName = upd.callbackQuery.from.firstName,
                            lastName = upd.callbackQuery.from.lastName,
                            userName = upd.callbackQuery.from.userName,
                            campaigns = it.campaigns + campaignForAdd
                        )
                    )
                } ?: {
                    service.createOrUpdateGroupUser(
                        UserInGroup(
                            upd.callbackQuery.from.id,
                            createDate = now(),
                            firstName = upd.callbackQuery.from.firstName,
                            lastName = upd.callbackQuery.from.lastName,
                            userName = upd.callbackQuery.from.userName,
                            campaigns = setOf(campaignForAdd)
                        )
                    )
                }.invoke()
                setTextToMessage(
                    text.userAddedToCampaign,
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
                execute(callbackAnswer.also { it.text = text.clbUserAddedToCampaign })
            }
            else -> {
                setTextToMessage(
                    text.errUserAddedToCampaign,
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.clbUserAddedToCampaign })
            }
        }
    }

    private fun surveyActions(command: UserState, upd: Update, callbackAnswer: AnswerCallbackQuery) {
        when (command) {
            SURVEY_DESCRIPTION -> {
            }
            else -> {
                setTextToMessage(
                    resourceText(text.errCommon),
                    upd.callbackQuery.message.messageId,
                    upd.callbackQuery.message.chatId
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.errClbCommon })
            }
        }
    }

    private fun surveyCreate(upd: Update, params: List<String>, menu: (msg: Message, text: String) -> Unit) {
        userStates[upd.callbackQuery.from.id] =
            UserData(SURVEY_NAME, upd.callbackQuery.from)
        sendMessage(text.msgSurveyActionsName, upd.callbackQuery.message.chatId)
    }

    private fun surveyDelete(upd: Update, params: List<String>, menu: (msg: Message, text: String) -> Unit) {
        userStates[upd.callbackQuery.from.id] =
            UserData(SURVEY_DELETE, upd.callbackQuery.from)
        when (params[1]) {
            "delete" -> {
                val surveys = userStates[upd.callbackQuery.from.id]?.campaign?.surveys
                if (!surveys.isNullOrEmpty()) {
                    editMessage(
                        EditMessageText().also { editMessage ->
                            editMessage.chatId = upd.callbackQuery.message.chatId.toString()
                            editMessage.messageId = upd.callbackQuery.message.messageId
                            editMessage.text = text.surveyOptions
                            editMessage.replyMarkup = InlineKeyboardMarkup().also { markup ->
                                markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                                    surveys.forEach {
                                        keyboard.add(listOf(InlineKeyboardButton().setText(it.name).setCallbackData("SURVEY_DELETE ${it.id}")))
                                    }
                                }
                            }
                        }
                    )
                } else {
                    deleteMessage(upd.callbackQuery.message)
                    end(upd, text.errNotFoundSurvey, menu)
                }
            }
            else -> try {
                service.deleteSurveyById(params[1].toLong())
                deleteMessage(upd.callbackQuery.message)
                end(upd, text.surveyDeleted, menu)
            } catch (t: Throwable) {
                editMessage(
                    EditMessageText().also { editMessage ->
                        editMessage.chatId = upd.callbackQuery.message.chatId.toString()
                        editMessage.messageId = upd.callbackQuery.message.messageId
                        editMessage.text = text.errSurveyDelete
                    }
                )
                log.error("error surveyDelete", t)
            }
        }
        SURVEY_DELETE
    }

    private fun showTaskList(upd: Update) {
        try {

            val message = SendMessage()

            val markupInline = InlineKeyboardMarkup()
            val rowsInline = ArrayList<List<InlineKeyboardButton>>()
            val rowInline = ArrayList<InlineKeyboardButton>()
            tasks.forEach { name, _ ->
                rowInline.add(
                    InlineKeyboardButton()
                        .setText(name)
                        .setCallbackData(name)
                )
            }
            // Set the keyboard to the markup
            rowsInline.add(rowInline)
            // Add it to the message
            markupInline.keyboard = rowsInline
            message.replyMarkup = markupInline
            message.text = "root/groups"

            sendMessage(message, upd.message.chatId)
        } catch (e: Exception) {
            log.error(e.message, e)
        }
    }

    private fun mainAdminMenu(message: Message, textMsg: String = text.mainMenu) =
        sendMessage(SendMessage().also { msg ->
            msg.text = textMsg
            msg.enableMarkdown(true)
            msg.replyMarkup = ReplyKeyboardMarkup().also { markup ->
                markup.selective = true
                markup.resizeKeyboard = true
                markup.oneTimeKeyboard = false
                markup.keyboard = ArrayList<KeyboardRow>().also { keyboard ->
                    keyboard.addElements(KeyboardRow().also {
                        it.add(text.addSuperAdmin)
                        it.add(text.removeSuperAdmin)
                    }, KeyboardRow().also {
                        it.add(text.sendToEveryUser)
                        it.add(text.sendToEveryGroup)
                        it.add(text.survey)
                    }, KeyboardRow().also {
                        it.add(text.addGroupToCampaign)
                        it.add(text.addAdminToCampaign)
                        it.add(text.createCampaign)
                    }, KeyboardRow().also {
                        it.add(text.removeGroupFromCampaign)
                        it.add(text.removeAdminFromCampaign)
                        it.add(text.removeCampaign)
                    })
                }
            }
        }, message.chatId)

    private fun superAdminMenu(message: Message) = sendMessage(SendMessage().also { msg ->
        msg.text = text.mainMenu
        msg.enableMarkdown(true)
        msg.replyMarkup = ReplyKeyboardMarkup().also { markup ->
            markup.selective = true
            markup.resizeKeyboard = true
            markup.oneTimeKeyboard = false
            markup.keyboard = ArrayList<KeyboardRow>().also { keyboard ->
                keyboard.addElements(KeyboardRow().also {
                    it.add(text.sendToEveryUser)
                    it.add(text.sendToEveryGroup)
                }, KeyboardRow().also {
                    it.add(text.addGroupToCampaign)
                    it.add(text.addAdminToCampaign)
                    it.add(text.createCampaign)
                }, KeyboardRow().also {
                    it.add(text.removeGroupFromCampaign)
                    it.add(text.removeAdminFromCampaign)
                    it.add(text.removeCampaign)
                })
            }
        }
    }, message.chatId)

    private fun adminMenu(message: Message) = sendMessage(SendMessage().also { msg ->
        msg.text = text.mainMenu
        msg.enableMarkdown(true)
        msg.replyMarkup = ReplyKeyboardMarkup().also { markup ->
            markup.selective = true
            markup.resizeKeyboard = true
            markup.oneTimeKeyboard = false
            markup.keyboard = ArrayList<KeyboardRow>().also { keyboard ->
                keyboard.add(KeyboardRow().also {
                    it.add(text.sendToEveryUser)
                    it.add(text.sendToEveryGroup)
                })
            }
        }
    }, message.chatId)

    private fun userMenu(message: Message) = sendMessage(SendMessage().also { msg ->
        msg.text = text.userMainMenu
        msg.enableMarkdown(true)
        msg.replyMarkup = ReplyKeyboardMarkup().also { markup ->
            markup.selective = true
            markup.resizeKeyboard = true
            markup.oneTimeKeyboard = false
            markup.keyboard = ArrayList<KeyboardRow>().also { keyboard ->
                keyboard.add(KeyboardRow().also {
                    it.add(text.joinToCampaign)
                })
            }
        }
    }, message.chatId)

    private fun resetMenu(message: Message, text: String) = sendMessage(SendMessage().also { msg ->
        msg.text = text
        msg.enableMarkdown(true)
        msg.replyMarkup = ReplyKeyboardMarkup().also { markup ->
            markup.selective = true
            markup.resizeKeyboard = true
            markup.oneTimeKeyboard = false
            markup.keyboard = ArrayList<KeyboardRow>().also { keyboard ->
                keyboard.add(KeyboardRow().also {
                    it.add(this.text.reset)
                })
            }
        }
    }, message.chatId)

    private fun sendMessage(messageText: String, chatId: Long) = try {
        val message = SendMessage().setChatId(chatId)
        log.debug("Send to chatId = $chatId\nMessage: \"$messageText\"")
        message.text = messageText
        execute(message)
    } catch (e: Exception) {
        log.warn(e.message, e)
    }

    private fun sendMessage(message: SendMessage, chatId: Long) = try {
        log.debug("Send to chatId = $chatId\nMessage: \"${message.text}\"")
        message.setChatId(chatId)
        execute<Message, SendMessage>(message)
    } catch (e: Exception) {
        log.warn(e.message, e)
    }

    private fun getAllUserChats(chats: List<Group>, userId: Int) = chats.filter {
        try {
            listOf("creator", "administrator", "member", "restricted").contains(getUser(it.groupId, userId).status)
        } catch (e: TelegramApiRequestException) {
            log.info("User: $userId not found in chat ${it.groupId}")
            false
        }
    }

    private fun msgToCampaign(groups: Iterable<Group>, upd: Update) = groups.forEach {
        execute(
            ForwardMessage(
                it.groupId,
                upd.message.chatId,
                upd.message.messageId
            )
        )
    }

    private fun msgToUsers(users: Iterable<UserInGroup>, upd: Update) = users.forEach {
        execute(
            ForwardMessage(
                it.userId.toLong(),
                upd.message.chatId,
                upd.message.messageId
            )
        )
    }

    private fun sendAvailableCampaignsList(text: String, command: String, chatId: Long, campaigns: Iterable<Campaign>) =
        sendMessage(SendMessage().also { msg ->
            msg.text = text
            msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
                markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                    campaigns.forEach {
                        keyboard.add(listOf(InlineKeyboardButton().setText(it.name).setCallbackData("$command ${it.id}")))
                    }
                }
            }
        }, chatId)

    private fun sendSurveyMsg(survey: Survey, chatId: Long) = sendMessage(SendMessage().also { msg ->
        msg.text = "${survey.name}\n${survey.description}\n${printQuestions(survey.questions)}"
        msg.replyMarkup = surveyMarkup(survey)
    }, chatId)

    private fun showSurvey(survey: Survey, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = upd.callbackQuery.message.chatId.toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = "${survey.name}\n${survey.description}\n${printQuestions(survey.questions)}"
        msg.replyMarkup = surveyMarkup(survey)
    })

    private fun showQuestions(survey: Survey, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = upd.callbackQuery.message.chatId.toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = "${survey.name}\n${survey.description}\n${printQuestions(survey.questions)}"
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                survey.questions.toList().sortedBy { it.sortPoints }.forEach {
                    keyboard.add(
                        listOf(
                            InlineKeyboardButton().setText(it.text)
                                .setCallbackData("$SURVEY_QUESTION_SELECT ${it.text}")
                        )
                    )
                }
                keyboard.add(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionSelectBack)
                            .setCallbackData("$SURVEY_QUESTION_SELECT_BACK")
                    )
                )
            }
        }
    })

    private fun editQuestion(question: Question, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = upd.callbackQuery.message.chatId.toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = printQuestions(setOf(question))
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(question.text)
                            .setCallbackData("$SURVEY_QUESTION_EDIT_TEXT ${question.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(question.text)
                            .setCallbackData("$SURVEY_QUESTION_EDIT_SORT ${question.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(question.text)
                            .setCallbackData("$SURVEY_OPTION_SELECT ${question.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(question.text)
                            .setCallbackData("$SURVEY_QUESTION_DELETE ${question.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionBack)
                            .setCallbackData("$SURVEY_QUESTION_BACK ${question.text}")
                    )
                )
            }
        }
    })

    private fun showOptions(question: Question, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = upd.callbackQuery.message.chatId.toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = question.options.let { printOptions(it) }
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                question.options.toList().sortedBy { it.sortPoints }.forEach {
                    keyboard.add(
                        listOf(
                            InlineKeyboardButton().setText(it.text)
                                .setCallbackData("$SURVEY_OPTION_SELECT ${it.text}")
                        )
                    )
                }
                keyboard.add(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionSelectBack)
                            .setCallbackData("$SURVEY_OPTION_SELECT_BACK")
                    )
                )
            }
        }
    })

    private fun editOption(option: Option, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = upd.callbackQuery.message.chatId.toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = printOptions(setOf(option))
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(option.text)
                            .setCallbackData("$SURVEY_OPTION_EDIT_TEXT ${option.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(option.text)
                            .setCallbackData("$SURVEY_OPTION_EDIT_SORT ${option.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(option.text)
                            .setCallbackData("$SURVEY_OPTION_EDIT_VALUE ${option.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(option.text)
                            .setCallbackData("$SURVEY_OPTION_DELETE ${option.text}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionBack)
                            .setCallbackData("$SURVEY_OPTION_BACK ${option.text}")
                    )
                )
            }
        }
    })

    private fun editMessage(msg: EditMessageText) = try {
        execute(msg)
    } catch (e: Exception) {
        log.warn(e.message, e)
    }

    private fun deleteMessage(msg: Message) = try {
        execute(DeleteMessage(msg.chatId, msg.messageId))
    } catch (e: Exception) {
        log.warn(e.message, e)
    }

    private fun setTextToMessage(text: String, msgId: Int, chatId: Long) = try {
        editMessage(
            EditMessageText().also { editMessage ->
                editMessage.chatId = chatId.toString()
                editMessage.messageId = msgId
                editMessage.text = text
            }
        )
    } catch (e: Exception) {
        log.error(e.message, e)
    }

    private fun end(upd: Update, msgTest: String = text.mainMenu, menu: (msg: Message, text: String) -> Unit) {
        userStates[upd.message?.from?.id ?: upd.callbackQuery.from.id]!!.state = NONE
        menu.invoke(upd.message ?: upd.callbackQuery.message, msgTest)
    }

    private fun surveyMarkup(survey: Survey) = InlineKeyboardMarkup().also { markup ->
        markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
            keyboard.addElements(
                listOf(
                    InlineKeyboardButton().setText(text.editQuestions)
                        .setCallbackData("$SURVEY_QUESTIONS")
                ),
                listOf(
                    InlineKeyboardButton().setText(text.editSurveyName)
                        .setCallbackData("$SURVEY_NAME")
                ),
                listOf(
                    InlineKeyboardButton().setText(text.editSurveyDescription)
                        .setCallbackData("$SURVEY_DESCRIPTION")
                ),
                listOf(
                    InlineKeyboardButton().setText(text.editSurveyDescription)
                        .setCallbackData("$SURVEY_SAVE")
                ),
                listOf(
                    InlineKeyboardButton().setText(text.editSurveyDescription)
                        .setCallbackData("$SURVEY_BACK")
                )
            )
        }
    }

    private fun getUser(chatId: Long, userId: Int) = execute(GetChatMember().setChatId(chatId).setUserId(userId))

    override fun getBotUsername(): String = botUsername

    override fun getBotToken(): String = botToken
}
