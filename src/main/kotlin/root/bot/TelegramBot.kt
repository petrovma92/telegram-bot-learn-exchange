package root.bot

import org.apache.log4j.Logger
import org.springframework.dao.DataIntegrityViolationException
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.ForwardMessage
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import root.data.MainAdmin
import root.data.Text
import root.data.UserData
import root.data.UserState
import root.service.Service
import java.time.OffsetDateTime.now
import java.util.ArrayList
import java.util.HashMap

import root.data.UserState.*
import root.data.dao.SurveyDAO
import root.data.entity.*
import root.libs.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat

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
    private val service: Service


    constructor(
        botUsername: String,
        botToken: String,
        tasks: Map<String, String>,
        text: Text,
        service: Service,
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
        service: Service,
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
//            todo SPLIT COMMAND HERE TO THREADS (TEST IF SOME PART WAS DELETED (what will happen with PASSED_SURVEY if SURVEY will be deleted))
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
                deleteMessage(update.callbackQuery.message)
                userStates.remove(sender.id)
            }
        } else if (update.message.isUserMessage) {
            val sender = update.message.from
//            todo if (update.message.isGroupMessage || update.message.isChannelMessage || update.message.isSuperGroupMessage) {
            when {
                mainAdmins.contains(MainAdmin(sender.id, sender.userName)) -> doMainAdminUpdate(update)
                else -> service.getSuperAdminById(sender.id)?.let { doSuperAdminUpdate(update, it) }
                    ?: service.getAdminById(sender.id)?.let {
                        try {
                            doAdminUpdate(update, it)
                        } catch (t: Throwable) {
                            t.printStackTrace()
                            log.error("error when try response to message: ${update.message.text}", t)
                            userStates.remove(sender.id)
                            sendMessage(mainAdminsMenu(text, text.errAdmins), fromId(update))
                        }
                    } ?: try {
                        doUserUpdate(update)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        log.error("error when try response to message: ${update.message.text}", t)
                        userStates.remove(sender.id)
                        sendMessage(mainUsersMenu(text, text.errUsers), fromId(update))
                    }
            }
        }
    }

    private fun doMainAdminUpdate(upd: Update) {
        val actionBack: () -> Unit = {
            when (userStates[message(upd).from.id]?.state) {
                MAIN_MENU_ADD_MISSION, MAIN_MENU_ADD_TASK, MAIN_MENU_ADD_ADMIN, MAIN_MENU_ADD_CAMPAIGN,
                MAIN_MENU_ADD_COMMON_CAMPAIGN, MAIN_MENU_ADD_GROUP, MAIN_MENU_ADD_SUPER_ADMIN,
                CAMPAIGN_FOR_SURVEY -> {
                    userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD, message(upd).from)
                    sendMessage(mainAdminAddMenu(text).apply {
                        replyMarkup = (replyMarkup as ReplyKeyboardMarkup).apply {
                            keyboard = createKeyboard(keyboard.flatten().toArrayList().apply {
                                addElements(
                                    0,
                                    KeyboardButton(this@TelegramBot.text.addMenuCampaign),
                                    KeyboardButton(this@TelegramBot.text.addMenuCommonCampaign),
                                    KeyboardButton(this@TelegramBot.text.addMenuGroup),
                                    KeyboardButton(this@TelegramBot.text.addMenuSuperAdmin)
                                )
                            })
                        }
                    }, fromId(upd))
                }
                MAIN_MENU_DELETE_MISSION, MAIN_MENU_DELETE_TASK, MAIN_MENU_DELETE_ADMIN, MAIN_MENU_DELETE_CAMPAIGN,
                MAIN_MENU_DELETE_COMMON_CAMPAIGN, MAIN_MENU_DELETE_GROUP, MAIN_MENU_DELETE_SUPER_ADMIN -> {
                    userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE, message(upd).from)
                    sendMessage(mainAdminDeleteMenu(text).apply {
                        replyMarkup = (replyMarkup as ReplyKeyboardMarkup).apply {
                            keyboard = createKeyboard(keyboard.flatten().toArrayList().apply {
                                addElements(
                                    0,
                                    KeyboardButton(this@TelegramBot.text.deleteMenuCampaign),
                                    KeyboardButton(this@TelegramBot.text.deleteMenuCommonCampaign),
                                    KeyboardButton(this@TelegramBot.text.deleteMenuGroup),
                                    KeyboardButton(this@TelegramBot.text.deleteMenuSuperAdmin)
                                )
                            })
                        }
                    }, fromId(upd))
                }
                else -> {
                    userStates.remove(message(upd).from.id)
                    sendMessage(mainAdminsMenu(text), fromId(upd))
                }
            }
        }

        when (userStates[message(upd).from.id]?.state) {
            MAIN_MENU_ADD -> {
                when (message(upd).text) {
                    text.addMenuCampaign -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_CAMPAIGN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgCreateCampaign, text.back), fromId(upd))
                    }
                    text.addMenuCommonCampaign -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_COMMON_CAMPAIGN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgCreateCommonCampaign, text.back), fromId(upd))
                    }
                    text.addMenuGroup -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_GROUP, message(upd).from)
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgGroupToCampaignSelectCamp,
                                MAIN_MENU_ADD_GROUP.toString(),
                                service.getAllCampaigns()
                            ), fromId(upd)
                        )
                    }
                    text.addMenuSuperAdmin -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_SUPER_ADMIN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgAddSuperAdmin, text.back), fromId(upd))
                    }
                    text.addMenuMission -> {
                        // todo refactor it userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_MISSION, message(upd).from)

                        sendMessage(msgBackMenu(text.msgSurvey, text.back), fromId(upd))

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendMessage(
                                msgAvailableCampaignsListDivideCommon(
                                    text.adminAvailableCampaignsSurveys,
                                    CAMPAIGN_FOR_SURVEY.toString(),
                                    availableCampaigns
                                ), fromId(upd)
                            )
                            userStates[message(upd).from.id] =
                                UserData(CAMPAIGN_FOR_SURVEY, message(upd).from)
                        } else {
                            sendMessage(mainAdminsMenu(text, text.msgNoCampaign), fromId(upd))
                            userStates.remove(message(upd).from.id)
                        }
                    }
                    text.addMenuTask -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_TASK, message(upd).from)
                        TODO("MAIN_MENU_ADD_TASK")
                    }
                    text.addMenuAdmin -> {
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgAdminToCampaignSelectCamp,
                                MAIN_MENU_ADD_ADMIN.toString(),
                                service.getAllCampaigns()
                            ), fromId(upd)
                        )
                    }
                    text.back -> actionBack.invoke()
                }
            }
            MAIN_MENU_DELETE -> {
                when (message(upd).text) {
                    text.deleteMenuCampaign -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_CAMPAIGN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgRemoveCampaign, text.back), fromId(upd))
                    }
                    text.deleteMenuCommonCampaign -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_COMMON_CAMPAIGN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgRemoveCommonCampaign, text.back), fromId(upd))
                    }
                    text.deleteMenuGroup -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_GROUP, message(upd).from)
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgRemoveGroupFromCampaign,
                                MAIN_MENU_DELETE_GROUP.toString(),
                                service.getAllCampaigns()
                            ), fromId(upd)
                        )
                    }
                    text.deleteMenuSuperAdmin -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_SUPER_ADMIN, message(upd).from)
                        sendMessage(msgBackMenu(text.msgDeleteSuperAdmin, text.back), fromId(upd))
                    }
                    text.deleteMenuMission -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_MISSION, message(upd).from)
                        TODO("MAIN_MENU_DELETE_MISSION")
                    }
                    text.deleteMenuTask -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_TASK, message(upd).from)
                        TODO("MAIN_MENU_DELETE_TASK")
                    }
                    text.deleteMenuAdmin -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_ADMIN, message(upd).from)
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgRemoveAdminFromCampaign,
                                MAIN_MENU_DELETE_ADMIN.toString(),
                                service.getAllCampaigns()
                            ), fromId(upd)
                        )
                    }
                    text.back -> actionBack.invoke()
                }
            }
            MAIN_MENU_ADD_SUPER_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val adminId = message(upd).text.toInt()

                        service.getSuperAdminById(adminId)?.run {
                            sendMessage(text.errAddSuperAdminAlreadyExist, fromId(upd))
                        } ?: {
                            service.saveSuperAdmin(SuperAdmin(userId = adminId, createDate = now()))
                            sendMessage(text.sucAddSuperAdmin, fromId(upd))
                        }.invoke()

                    } catch (t: Throwable) {
                        sendMessage(text.errAddSuperAdmin, fromId(upd))
                        log.error("SuperAdmin creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_SUPER_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        service.deleteSuperAdminById(message(upd).text.toInt())
                        sendMessage(text.sucRemoveSuperAdmin, fromId(upd))
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveSuperAdmin, fromId(upd))
                        log.error("SuperAdmin deleting err.", t)
                    }
                }
            }
            MAIN_MENU_ADD_COMMON_CAMPAIGN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val newCampName = message(upd).text

                        service.createCampaign(
                            Campaign(
                                name = newCampName,
                                createDate = now(),
                                common = true,
                                groups = emptySet()
                            )
                        )

                        sendMessage(text.sucCreateCommonCampaign, fromId(upd))
                    } catch (e: DataIntegrityViolationException) {
                        sendMessage(text.errCreateCommonCampaignAlreadyExist, fromId(upd))
                        log.error("Campaign creating err.", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errCreateCommonCampaign, fromId(upd))
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_COMMON_CAMPAIGN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val newCampName = message(upd).text

                        service.deleteCampaignByName(newCampName)

                        sendMessage(text.sucRemoveCommonCampaign, fromId(upd))
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveCommonCampaign, fromId(upd))
                        log.error("Campaign remove err.", t)
                    }
                }
            }
            MAIN_MENU_ADD_CAMPAIGN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val newCampName = message(upd).text

                        service.createCampaign(Campaign(name = newCampName, createDate = now(), groups = emptySet()))

                        sendMessage(text.sucCreateCampaign, fromId(upd))
                    } catch (e: DataIntegrityViolationException) {
                        sendMessage(text.errCreateCampaignAlreadyExist, fromId(upd))
                        log.error("Campaign creating err.", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errCreateCampaign, fromId(upd))
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_CAMPAIGN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val newCampName = message(upd).text

                        service.deleteCampaignByName(newCampName)

                        sendMessage(text.sucRemoveCampaign, fromId(upd))
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveCampaign, fromId(upd))
                        log.error("Campaign remove err.", t)
                    }
                }
            }
            MAIN_MENU_ADD_GROUP -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex())
                        val groupId = params[0].toLong()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (addedGroup, campaign) = service.addGroup(
                            userId = userId.toInt(),
                            groupId = groupId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessAddGroup,
                                    "group.id" to "${addedGroup.groupId}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: NoAccessException) {
                        sendMessage(text.errAddGroupAccessDenied, fromId(upd))
                        log.error("Group creating err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errAddGroup, fromId(upd))
                        log.error("Group creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_GROUP -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex(), 2)
                        val groupId = params[0].toLong()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (deletedGroup, campaign) = service.deleteGroup(
                            userId = userId.toInt(),
                            groupId = groupId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessDeleteGroup,
                                    "group.id" to "${deletedGroup.groupId}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: AdminNotFoundException) {
                        sendMessage(text.errDeleteGroupNotFound, fromId(upd))
                        log.error("Group deleting err (not found).", e)
                    } catch (e: NoAccessException) {
                        sendMessage(text.errDeleteGroupAccessDenied, fromId(upd))
                        log.error("Group deleting err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errDeleteGroup, fromId(upd))
                        log.error("Group deleting err.", t)
                    }
                }
            }
            MAIN_MENU_ADD_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex())
                        val adminId = params[0].toInt()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (addedAdmin, campaign) = service.addAdmin(
                            userId = userId.toInt(),
                            adminId = adminId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessAddAdmin,
                                    "admin.desc" to "${addedAdmin.userId} ${addedAdmin.userName}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: NoAccessException) {
                        sendMessage(text.errAddAdminAccessDenied, fromId(upd))
                        log.error("AdminGroup creating err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errAddAdmin, fromId(upd))
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex(), 2)
                        val adminId = params[0].toInt()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (deletedAdmin, campaign) = service.deleteAdmin(
                            userId = userId.toInt(),
                            adminId = adminId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessDeleteAdmin,
                                    "admin.desc" to "${deletedAdmin.userId} ${deletedAdmin.userName}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: AdminNotFoundException) {
                        sendMessage(text.errDeleteAdminNotFound, fromId(upd))
                        log.error("AdminGroup deleting err (not found).", e)
                    } catch (e: NoAccessException) {
                        sendMessage(text.errDeleteAdminAccessDenied, fromId(upd))
                        log.error("AdminGroup deleting err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errDeleteAdmin, fromId(upd))
                        log.error("AdminGroup deleting err.", t)
                    }
                }
            }
            MAIN_MENU_STATISTIC -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> when (message(upd).text) {
                        text.sendCampaignsTable -> {
                            sendTable(message(upd).chatId, service.getAllCampaigns())
                        }
                        text.sendSuperAdminTable -> {
                            sendTable(message(upd).chatId, service.getAllSuperAdmins())
                        }
                        text.sendSurveysTable -> {
                            sendMessage(
                                msgAvailableCampaignsList(
                                    text.msgSurveysTable,
                                    "$GET_EXCEL_TABLE_SURVEY",
                                    service.getAllCampaigns()
                                ), fromId(upd)
                            )
                        }
                        text.sendAdminsTable -> {
                            sendMessage(
                                msgAvailableCampaignsList(
                                    text.msgAdminsTable,
                                    "$GET_EXCEL_TABLE_ADMINS",
                                    service.getAllCampaigns()
                                ), fromId(upd)
                            )
                        }
                        text.sendUsersInCampaign -> {
                            sendMessage(
                                msgAvailableCampaignsList(
                                    text.msgUsersInCampaign,
                                    "$GET_EXCEL_TABLE_USERS_IN_CAMPAIGN",
                                    service.getAllCampaigns()
                                ), fromId(upd)
                            )
                        }
                    }
                }
            }
            SURVEY_CREATE -> {
                val survey = Survey(
                    name = message(upd).text,
                    createDate = now(),
                    questions = HashSet(),
                    campaign = userStates[message(upd).from.id]!!.campaign!!
                )

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.survey = survey
                }
                editSurvey(survey, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_NAME -> {
                val survey = userStates[message(upd).from.id]?.survey?.also { it.name = message(upd).text }
                    ?: Survey(
                        name = message(upd).text,
                        createDate = now(),
                        questions = HashSet(),
                        campaign = userStates[upd.callbackQuery.from.id]!!.campaign!!
                    )

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.survey = survey
                }
                editSurvey(survey, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_DESCRIPTION -> {
                val survey = userStates[message(upd).from.id]?.survey!!.also { it.description = message(upd).text }

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.survey = survey
                }
                editSurvey(survey, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_QUESTION_CREATE -> {
                val question = Question(text = message(upd).text, options = HashSet())

                userStates[message(upd).from.id]!!.apply {
                    state = NONE
                    this.question = question
                }
                editQuestion(question, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_QUESTION_EDIT_TEXT -> {
                val question = userStates[message(upd).from.id]?.question!!.also { it.text = message(upd).text }

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.question = question
                }
                editQuestion(question, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_QUESTION_EDIT_SORT -> {
                try {
                    val question =
                        userStates[message(upd).from.id]?.question!!.also { it.sortPoints = message(upd).text.toInt() }

                    userStates[message(upd).from.id]!!.apply {
                        this.state = NONE
                        this.question = question
                    }
                    editQuestion(question, userStates[message(upd).from.id]!!.updCallback!!)
                } catch (t: Throwable) {
                    log.warn("error read sortPoints", t)

                    enterText(
                        userStates[message(upd).from.id]!!.updCallback!!.callbackQuery!!.message,
                        text.errSurveyEnterNumber,
                        text.backToSurveyQuestionMenu,
                        SURVEY_QUESTION_SELECT
                    )
                }
            }
            SURVEY_OPTION_CREATE -> {
                val option = Option(text = message(upd).text)

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.option = option
                }
                editOption(option, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_OPTION_EDIT_TEXT -> {
                val option = userStates[message(upd).from.id]?.option!!.also { it.text = message(upd).text }

                userStates[message(upd).from.id]!!.apply {
                    this.state = NONE
                    this.option = option
                }
                editOption(option, userStates[message(upd).from.id]!!.updCallback!!)
            }
            SURVEY_OPTION_EDIT_CORRECT -> {
                try {
                    val option =
                        userStates[message(upd).from.id]?.option!!.also {
                            it.correct = message(upd).text.equals("true", ignoreCase = true)
                        }

                    userStates[message(upd).from.id]!!.apply {
                        this.state = NONE
                        this.option = option
                    }
                    editOption(option, userStates[message(upd).from.id]!!.updCallback!!)
                } catch (t: Throwable) {
                    log.warn("error read sortPoints", t)

                    enterText(
                        userStates[message(upd).from.id]!!.updCallback!!.callbackQuery!!.message,
                        text.errSurveyEnterNumber,
                        text.backToSurveyQuestionMenu,
                        SURVEY_QUESTION_SELECT
                    )
                }
            }
            SURVEY_OPTION_EDIT_SORT -> {
                try {
                    val option =
                        userStates[message(upd).from.id]?.option!!.also { it.sortPoints = message(upd).text.toInt() }

                    userStates[message(upd).from.id]!!.apply {
                        state = NONE
                        survey!!.questions = survey!!.questions.toHashSet().apply { add(question!!) }
                        this.option = option
                    }
                    editOption(option, userStates[message(upd).from.id]!!.updCallback!!)
                } catch (t: Throwable) {
                    log.warn("error read sortPoints", t)

                    enterText(
                        userStates[message(upd).from.id]!!.updCallback!!.callbackQuery!!.message,
                        text.errSurveyEnterNumber,
                        text.backToSurveyQuestionMenu,
                        SURVEY_QUESTION_SELECT
                    )
                }
            }
            else -> {
                when (message(upd).text) {
                    text.mainMenuAdd -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD, message(upd).from)
                        sendMessage(mainAdminAddMenu(text).apply {
                            replyMarkup = (replyMarkup as ReplyKeyboardMarkup).apply {
                                keyboard = createKeyboard(keyboard.flatten().toArrayList().apply {
                                    addElements(
                                        0,
                                        KeyboardButton(this@TelegramBot.text.addMenuCampaign),
                                        KeyboardButton(this@TelegramBot.text.addMenuCommonCampaign),
                                        KeyboardButton(this@TelegramBot.text.addMenuGroup),
                                        KeyboardButton(this@TelegramBot.text.addMenuSuperAdmin)
                                    )
                                })
                            }
                        }, fromId(upd))
                    }
                    text.mainMenuDelete -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE, message(upd).from)
                        sendMessage(mainAdminDeleteMenu(text).apply {
                            replyMarkup = (replyMarkup as ReplyKeyboardMarkup).apply {
                                keyboard = createKeyboard(keyboard.flatten().toArrayList().apply {
                                    addElements(
                                        0,
                                        KeyboardButton(this@TelegramBot.text.deleteMenuCampaign),
                                        KeyboardButton(this@TelegramBot.text.deleteMenuCommonCampaign),
                                        KeyboardButton(this@TelegramBot.text.deleteMenuGroup),
                                        KeyboardButton(this@TelegramBot.text.deleteMenuSuperAdmin)
                                    )
                                })
                            }
                        }, fromId(upd))
                    }
                    text.mainMenuMessages -> {
                        sendMessage(msgBackMenu(text.msgSendToEveryGroup, text.reset), fromId(upd))

                        service.getAllCampaigns().toList().run {
                            if (isNotEmpty()) {
                                sendMessage(
                                    msgAvailableCampaignsListDivideCommon(
                                        text.adminAvailableCampaigns,
                                        CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                        this
                                    ), fromId(upd)
                                )
                                userStates[message(upd).from.id] =
                                    UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, message(upd).from)
                            }
                        }
                    }
                    text.mainMenuStatistic -> {
                        userStates[message(upd).from.id] =
                            UserData(MAIN_MENU_STATISTIC, message(upd).from)
                        sendMessage(mainAdminStatisticMenu(text), fromId(upd))
                    }
                    text.back -> actionBack.invoke()
                    else -> {
                        when (userStates[message(upd).from.id]?.state) {
                            MAIN_MENU_STATISTIC -> sendMessage(mainAdminStatisticMenu(text), fromId(upd))
                            MAIN_MENU_DELETE -> sendMessage(mainAdminDeleteMenu(text), fromId(upd))
                            MAIN_MENU_ADD -> sendMessage(mainAdminAddMenu(text), fromId(upd))
                            CAMPAIGN_FOR_SEND_GROUP_MSG -> sendMessage(mainAdminsMenu(text), fromId(upd))
                            else -> {
                                sendMessage(mainAdminsMenu(text), fromId(upd))
                                log.warn("Not supported action!\n${message(upd)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun doSuperAdminUpdate(upd: Update, admin: SuperAdmin) {
        if (!admin.equals(message(upd).from)) {
            admin.update(message(upd).from)
            service.saveSuperAdmin(admin)
        }
        when (userStates[message(upd).from.id]?.state) {
            CREATE_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val name = message(upd).text

                        service.createCampaign(
                            Campaign(
                                name = name,
                                createDate = now(),
                                groups = HashSet()
                            )
                        )

                        end(upd, text.sucCreateCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errCreateCampaign, fromId(upd))
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            MAIN_MENU_ADD_ADMIN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val ids = message(upd).text.split("\\s+".toRegex(), 2)
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
                                    campaigns = hashSetOf(camp)
                                )
                            )

//                            end(upd, text.sucAdminToCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                        } ?: {
                            sendMessage(text.errCampaignNotFound, fromId(upd))
                        }.invoke()

                    } catch (t: Throwable) {
                        sendMessage(text.errAdminToCampaign, fromId(upd))
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            ADD_GROUP_TO_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val ids = message(upd).text.split("\\s+".toRegex(), 2)
                        val groupId = ids[0].toLong()
                        val name = ids[1]

                        service.getCampaignByName(name)?.let {
                            val group = service.createGroup(Group(groupId, now()))
                            it.groups = it.groups.toHashSet().also { gr -> gr.add(group) }
                            service.updateCampaign(it)
                        } ?: {
                            sendMessage(text.errCampaignNotFound, fromId(upd))
                        }.invoke()

                        end(upd, text.sucGroupToCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errGroupToCampaign, fromId(upd))
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            REMOVE_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        service.deleteCampaignByName(message(upd).text)
                        end(upd, text.sucRemoveCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveCampaign, fromId(upd))
                        log.error("Campaign creating err.", t)
                    }
                }
            }
            REMOVE_ADMIN_FROM_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex(), 2)
                        val adminForDelete = service.getAdminById(params[0].toInt())
                        adminForDelete!!.campaigns =
                            adminForDelete.campaigns.filter { it.name != params[1] }.toHashSet()

                        service.saveAdmin(adminForDelete)
                        end(upd, text.sucRemoveAdminFromCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveAdminFromCampaign, fromId(upd))
                        log.error("AdminGroup deleting err.", t)
                    }
                }
            }
            REMOVE_GROUP_FROM_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex(), 2)
                        val groupId = params[0].toLong()
                        val campaign = service.getCampaignByName(params[1])
                        campaign!!.groups = campaign.groups.filter { it.groupId != groupId }.toHashSet()

                        service.updateCampaign(campaign)
                        end(upd, text.sucRemoveGroupFromCampaign) { msg: Message, _: String -> superAdminMenu(msg) }
                    } catch (t: Throwable) {
                        sendMessage(text.errRemoveGroupFromCampaign, fromId(upd))
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            MSG_TO_USERS -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val users = userStates[message(upd).from.id]?.users
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
                        sendMessage(text.errMsgToUsers, fromId(upd))
                        log.error("error msgToUsers", t)
                    }
                }
            }
            MSG_TO_CAMPAIGN -> {
                when (message(upd).text) {
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> try {
                        val groups = userStates[message(upd).from.id]?.groups
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
                        sendMessage(text.errMsgToCampaign, fromId(upd))
                        log.error("error msgToUsers", t)
                    }
                }
            }
            else -> {
                when (message(upd).text) {
                    text.removeCampaign -> {
                        sendMessage(msgBackMenu(text.msgRemoveCampaign, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(REMOVE_CAMPAIGN, message(upd).from)
                    }
                    text.removeAdminFromCampaign -> {
                        sendMessage(msgBackMenu(text.msgRemoveAdminFromCampaign, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(REMOVE_ADMIN_FROM_CAMPAIGN, message(upd).from)
                    }
                    text.removeGroupFromCampaign -> {
                        sendMessage(msgBackMenu(text.msgRemoveGroupFromCampaign, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(REMOVE_GROUP_FROM_CAMPAIGN, message(upd).from)
                    }
                    text.addAdminToCampaign -> {
                        sendMessage(msgBackMenu(text.msgAdminToCampaignAdminId, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(MAIN_MENU_ADD_ADMIN, message(upd).from)
                    }
                    text.addGroupToCampaign -> {
                        sendMessage(msgBackMenu(text.msgGroupToCampaign, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(ADD_GROUP_TO_CAMPAIGN, message(upd).from)
                    }
                    text.createCampaign -> {
                        sendMessage(msgBackMenu(text.msgCreateCampaign, text.reset), fromId(upd))
                        userStates[message(upd).from.id] =
                            UserData(CREATE_CAMPAIGN, message(upd).from)
                    }
                    text.sendToEveryUser -> {
                        sendMessage(msgBackMenu(text.msgSendToEveryUser, text.reset), fromId(upd))

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendMessage(
                                msgAvailableCampaignsListDivideCommon(
                                    text.adminAvailableCampaigns,
                                    CAMPAIGN_FOR_SEND_USERS_MSG.toString(),
                                    availableCampaigns
                                ), fromId(upd)
                            )
                            userStates[message(upd).from.id] =
                                UserData(CAMPAIGN_FOR_SEND_USERS_MSG, message(upd).from)
                        }
                    }
                    text.sendToEveryGroup -> {
                        sendMessage(msgBackMenu(text.msgSendToEveryGroup, text.reset), fromId(upd))

                        val availableCampaigns = service.getAllCampaigns().toList()

                        if (availableCampaigns.isNotEmpty()) {
                            sendMessage(
                                msgAvailableCampaignsListDivideCommon(
                                    text.adminAvailableCampaigns,
                                    CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                    availableCampaigns
                                ), fromId(upd)
                            )
                            userStates[message(upd).from.id] =
                                UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, message(upd).from)
                        }
                    }
                    text.reset -> {
                        userStates.remove(message(upd).from.id)
                        superAdminMenu(message(upd))
                    }
                    else -> {
                        superAdminMenu(message(upd))
                    }
                }
            }
        }
    }

    private fun doAdminUpdate(upd: Update, admin: Admin) {
        if (!admin.equals(message(upd).from)) {
            admin.update(message(upd).from)
            service.saveAdmin(admin)
        }
        val actionBack: () -> Unit = {
            when (userStates[message(upd).from.id]?.state) {
                MAIN_MENU_ADD_MISSION, MAIN_MENU_ADD_TASK, MAIN_MENU_ADD_ADMIN -> {
                    userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD, message(upd).from)
                    sendMessage(mainAdminAddMenu(text), fromId(upd))
                }
                MAIN_MENU_DELETE_MISSION, MAIN_MENU_DELETE_TASK, MAIN_MENU_DELETE_ADMIN -> {
                    userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE, message(upd).from)
                    sendMessage(mainAdminDeleteMenu(text), fromId(upd))
                }
                else -> {
                    userStates.remove(message(upd).from.id)
                    sendMessage(mainAdminsMenu(text, text.infoForAdmin), fromId(upd))
                }
            }
        }

        when (userStates[admin.userId]?.state) {
            MAIN_MENU_ADD -> {
                when (message(upd).text) {
                    text.addMenuMission -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_MISSION, message(upd).from)
                        TODO("MAIN_MENU_ADD_MISSION")
                    }
                    text.addMenuTask -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD_TASK, message(upd).from)
                        TODO("MAIN_MENU_ADD_TASK")
                    }
                    text.addMenuAdmin -> {
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgAdminToCampaignSelectCamp,
                                MAIN_MENU_ADD_ADMIN.toString(),
                                admin.campaigns
                            ), fromId(upd)
                        )
                    }
                    text.back -> actionBack.invoke()
                }
            }
            MAIN_MENU_DELETE -> {
                when (message(upd).text) {
                    text.deleteMenuMission -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_MISSION, message(upd).from)
                        TODO("MAIN_MENU_DELETE_MISSION")
                    }
                    text.deleteMenuTask -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_TASK, message(upd).from)
                        TODO("MAIN_MENU_DELETE_TASK")
                    }
                    text.deleteMenuAdmin -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE_ADMIN, message(upd).from)
                        sendMessage(
                            msgAvailableCampaignsListDivideCommon(
                                text.msgRemoveAdminFromCampaign,
                                MAIN_MENU_DELETE_ADMIN.toString(),
                                service.getAllCampaigns()
                            ), fromId(upd)
                        )
                    }
                    text.back -> actionBack.invoke()
                }
            }
            MAIN_MENU_ADD_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex())
                        val adminId = params[0].toInt()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (addedAdmin, campaign) = service.addAdmin(
                            userId = userId.toInt(),
                            adminId = adminId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessAddAdmin,
                                    "admin.desc" to "${addedAdmin.userId} ${addedAdmin.userName}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: NoAccessException) {
                        sendMessage(text.errAddAdminAccessDenied, fromId(upd))
                        log.error("AdminGroup creating err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errAddAdmin, fromId(upd))
                        log.error("AdminGroup creating err.", t)
                    }
                }
            }
            MAIN_MENU_DELETE_ADMIN -> {
                when (message(upd).text) {
                    text.back -> actionBack.invoke()
                    else -> try {
                        val params = message(upd).text.split("\\s+".toRegex(), 2)
                        val adminId = params[0].toInt()
                        val camp = userStates[message(upd).from.id]!!.campaign!!

                        val userId = fromId(upd)

                        val (deletedAdmin, campaign) = service.deleteAdmin(
                            userId = userId.toInt(),
                            adminId = adminId,
                            camp = camp,
                            maimAdmins = mainAdmins
                        )

                        sendMessage(
                            msgBackMenu(
                                resourceText(
                                    text.msgSuccessDeleteAdmin,
                                    "admin.desc" to "${deletedAdmin.userId} ${deletedAdmin.userName}",
                                    "camp.desc" to "${campaign.id} ${campaign.name}"
                                ), text.back
                            ), userId
                        )

                    } catch (e: AdminNotFoundException) {
                        sendMessage(text.errDeleteAdminNotFound, fromId(upd))
                        log.error("AdminGroup deleting err (not found).", e)
                    } catch (e: NoAccessException) {
                        sendMessage(text.errDeleteAdminAccessDenied, fromId(upd))
                        log.error("AdminGroup deleting err (access denied).", e)
                    } catch (t: Throwable) {
                        sendMessage(text.errDeleteAdmin, fromId(upd))
                        log.error("AdminGroup deleting err.", t)
                    }
                }
            }
            else -> {
                when (message(upd).text) {
                    text.mainMenuAdd -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_ADD, message(upd).from)
                        sendMessage(mainAdminAddMenu(text), fromId(upd))
                    }
                    text.mainMenuDelete -> {
                        userStates[message(upd).from.id] = UserData(MAIN_MENU_DELETE, message(upd).from)
                        sendMessage(mainAdminDeleteMenu(text), fromId(upd))
                    }
                    text.mainMenuMessages -> {
                        sendMessage(msgBackMenu(text.msgSendToEveryGroup, text.reset), fromId(upd))

                        if (admin.campaigns.isNotEmpty()) {
                            sendMessage(
                                msgAvailableCampaignsListDivideCommon(
                                    text.adminAvailableCampaigns,
                                    CAMPAIGN_FOR_SEND_GROUP_MSG.toString(),
                                    admin.campaigns
                                ), fromId(upd)
                            )
                            userStates[message(upd).from.id] =
                                UserData(CAMPAIGN_FOR_SEND_GROUP_MSG, message(upd).from)
                        }
                    }
                    text.mainMenuStatistic -> {
                        userStates[message(upd).from.id] =
                            UserData(MAIN_MENU_STATISTIC, message(upd).from)
                        sendMessage(mainAdminStatisticMenu(text), fromId(upd))
                    }
                    text.back -> actionBack.invoke()
                    else -> {
                        when (userStates[message(upd).from.id]?.state) {
                            MAIN_MENU_STATISTIC -> sendMessage(mainAdminStatisticMenu(text), fromId(upd))
                            MAIN_MENU_DELETE -> sendMessage(mainAdminDeleteMenu(text), fromId(upd))
                            MAIN_MENU_ADD -> sendMessage(mainAdminAddMenu(text), fromId(upd))
                            CAMPAIGN_FOR_SEND_GROUP_MSG -> sendMessage(mainAdminsMenu(text), fromId(upd))
                            else -> {
                                sendMessage(mainAdminsMenu(text, text.infoForAdmin), fromId(upd))
                                log.warn("Not supported action!\n${message(upd)}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun doUserUpdate(upd: Update) = when (userStates[message(upd).from.id]?.state) {
        RESET -> sendMessage(mainUsersMenu(text), fromId(upd))
        else -> when (message(upd).text) {
            text.userMainMenuCampaigns -> {
                userStates[message(upd).from.id] = UserData(USER_MENU_ACTIVE_CAMPAIGN, message(upd).from)
                sendMessage(
                    userCampaignsMenu(text, service.getAllCampaignByUserId(message(upd).from.id)),
                    fromId(upd)
                )
            }
            text.userMainMenuStatus -> {
                userStates[message(upd).from.id] = UserData(USER_MENU_STATUS, message(upd).from)
                sendMessage(
                    userStatusMenu(
                        text,
                        service.getAllPassedSurveysByUser(stubUserInCampaign(userId = message(upd).from.id))
                    ), fromId(upd)
                )
            }
            text.userMainMenuAccount -> {
                userStates[message(upd).from.id] = UserData(USER_MENU_MY_ACCOUNT, message(upd).from)
                sendMessage(userAccountMenu(text), fromId(upd))
            }
            else -> sendMessage(mainUsersMenu(text), fromId(upd))
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

        val errorAnswer = {
            setTextToMessage(
                resourceText(text.errCommon),
                upd.callbackQuery.message.messageId,
                fromId(upd)
            )
            userStates.remove(upd.callbackQuery.from.id)
            execute(callbackAnswer.also { it.text = text.errClbCommon })
        }

        when (callBackCommand) {
            SURVEY_CREATE -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(upd.callbackQuery.message, text.msgSurveyActionsName, text.backToSurveyCRUDMenu, SURVEY_BACK)
            }
            SURVEY_DELETE -> {
                service.deleteSurveyById(params[1].toLong())

                showSurveys(
                    service.getSurveyByCampaign(userStates[upd.callbackQuery.from.id]!!.campaign!!).toList(),
                    upd
                )
            }
            SURVEY_SAVE -> {
                userStates[upd.callbackQuery.from.id]!!.campaign?.let {
                    val survey = userStates[upd.callbackQuery.from.id]!!.survey!!
                    survey.campaign = it
                    service.saveSurvey(fixSurvey(survey))
                }

                editMessage(
                    upd.callbackQuery.message,
                    msgAvailableCampaignsListDivideCommon(
                        text.clbSurveySave,
                        CAMPAIGN_FOR_SURVEY.toString(),
                        service.getAllCampaigns().toList()
                    )
                )
            }
            SURVEY_EDIT -> {
                userStates[upd.callbackQuery.from.id]!!.survey = service.getSurveyById(params[1].toLong())
                editSurvey(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbEditSurvey })
            }
            SURVEY -> {
                editSurvey(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbEditSurvey })
            }
            SURVEY_NAME -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(upd.callbackQuery.message, text.msgSurveyActionsName, text.backToSurveyMenu, SURVEY)
            }
            SURVEY_DESCRIPTION -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(upd.callbackQuery.message, text.msgSurveyActionsDesc, text.backToSurveyMenu, SURVEY)
            }
            SURVEY_QUESTION_CREATE -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyQuestionActionsText,
                    text.backToSurveyQuestionsListMenu,
                    SURVEY_QUESTIONS
                )
            }
            SURVEY_QUESTION_EDIT_TEXT -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyQuestionActionsText,
                    text.backToSurveyQuestionMenu,
                    SURVEY_QUESTION_SELECT
                )
            }
            SURVEY_QUESTION_EDIT_SORT -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyQuestionActionsSort,
                    text.backToSurveyQuestionMenu,
                    SURVEY_QUESTION_SELECT
                )
            }
            SURVEY_OPTION_CREATE -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyOptionActionsText,
                    text.backToSurveyQuestionMenu,
                    SURVEY_OPTION_SELECT_BACK
                )
            }
            SURVEY_OPTION_EDIT_TEXT -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyOptionActionsText,
                    text.backToSurveyOptionMenu,
                    SURVEY_OPTION_EDIT_BACK
                )
            }
            SURVEY_OPTION_EDIT_CORRECT -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyOptionActionsCorrect,
                    text.backToSurveyOptionMenu,
                    SURVEY_OPTION_EDIT_BACK
                )
            }
            SURVEY_OPTION_EDIT_SORT -> {
                userStates[upd.callbackQuery.from.id]!!.apply {
                    this.state = callBackCommand
                    this.updCallback = upd
                }
                enterText(
                    upd.callbackQuery.message,
                    text.msgSurveyOptionActionsSort,
                    text.backToSurveyOptionMenu,
                    SURVEY_OPTION_EDIT_BACK
                )
            }
            SURVEY_BACK -> {
                deleteMessage(upd.callbackQuery.message)
                sendMessage(mainAdminsMenu(text), fromId(upd))
            }
            SURVEY_OPTION_SELECT_BACK -> {
                editQuestion(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurvey })
            }
            SURVEY_QUESTIONS -> {
                userStates[upd.callbackQuery.from.id]!!.question?.let {
                    userStates[upd.callbackQuery.from.id]!!.survey!!.questions =
                        userStates[upd.callbackQuery.from.id]!!.survey!!.questions.toHashSet().apply { add(it) }
                    userStates[upd.callbackQuery.from.id]!!.question = null
                    showQuestions(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                } ?: {
                    showQuestions(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                    execute(callbackAnswer.also { it.text = text.clbSurveyQuestions })
                }.invoke()
            }
            SURVEY_QUESTION_SELECT -> {
                userStates[upd.callbackQuery.from.id]!!.question =
                    userStates[upd.callbackQuery.from.id]!!.survey!!.questions.first { it.text.hashCode() == params[1].toInt() }
                editQuestion(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyQuestionEdit })
            }
            SURVEY_QUESTION_DELETE -> {
                val survey = userStates[upd.callbackQuery.from.id]!!.survey!!
                survey.questions =
                    survey.questions.toHashSet().apply { remove(userStates[upd.callbackQuery.from.id]!!.question) }
                userStates[upd.callbackQuery.from.id]!!.question = null

                showQuestions(userStates[upd.callbackQuery.from.id]!!.survey!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyQuestionDeleted })
            }
            SURVEY_OPTION_DELETE -> {
                val question = userStates[upd.callbackQuery.from.id]!!.question!!
                question.options =
                    question.options.toHashSet().apply { remove(userStates[upd.callbackQuery.from.id]!!.option) }
                userStates[upd.callbackQuery.from.id]!!.option = null

                showOptions(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyOptionDeleted })
            }
            SURVEY_OPTIONS -> {
                userStates[upd.callbackQuery.from.id]!!.option?.let {
                    userStates[upd.callbackQuery.from.id]!!.question!!.options =
                        userStates[upd.callbackQuery.from.id]!!.question!!.options.toHashSet().apply { add(it) }
                    userStates[upd.callbackQuery.from.id]!!.option = null
                    showOptions(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                } ?: {
                    showOptions(userStates[upd.callbackQuery.from.id]!!.question!!, upd)
                    execute(callbackAnswer.also { it.text = text.clbSurveyOptions })
                }.invoke()
            }
            SURVEY_OPTION_SELECT -> {
                userStates[upd.callbackQuery.from.id]!!.option =
                    userStates[upd.callbackQuery.from.id]!!.question!!.options.first { it.text.hashCode() == params[1].toInt() }
                editOption(userStates[upd.callbackQuery.from.id]!!.option!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyOptions })
            }
            SURVEY_OPTION_EDIT_BACK -> {
                editOption(userStates[upd.callbackQuery.from.id]!!.option!!, upd)
                execute(callbackAnswer.also { it.text = text.clbSurveyOptions })
            }

            GET_EXCEL_TABLE_SURVEY -> {
                deleteMessage(upd.callbackQuery.message)
                sendTable(
                    fromId(upd),
                    service.getSurveyByCampaignId(params[1].toLong())
                )
            }
            GET_EXCEL_TABLE_USERS_IN_CAMPAIGN -> {
                deleteMessage(upd.callbackQuery.message)
                sendTable(
                    fromId(upd),
                    service.getUsersByCampaignId(params[1].toLong())
                )
            }
            GET_EXCEL_TABLE_ADMINS -> {
                deleteMessage(upd.callbackQuery.message)
                sendTable(
                    fromId(upd),
                    service.getAdminsByCampaigns(setOf(stubCampaign(id = params[1].toLong())))
                )
            }

            CAMPAIGN_FOR_SEND_GROUP_MSG -> if (userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_GROUP_MSG) try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()
                val groups = campaign.groups

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_CAMPAIGN, upd.callbackQuery.from, groups = groups)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryGroup })
                setTextToMessage(
                    resourceText(text.sucSendMessageToEveryGroup, "campaign.name" to campaign.name),
                    upd.callbackQuery.message.messageId,
                    fromId(upd)
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_GROUP_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryGroup })
                throw t
            }
            else errorAnswer.invoke()
            CAMPAIGN_FOR_SEND_USERS_MSG -> if (userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SEND_USERS_MSG) try {

                val users = service.getUsersByCampaignId(params[1].toLong())

                userStates[upd.callbackQuery.from.id] =
                    UserData(MSG_TO_USERS, upd.callbackQuery.from, users = users)
                execute(callbackAnswer.also { it.text = text.clbSendMessageToEveryUsers })
                setTextToMessage(
                    resourceText("${text.sucSendMessageToEveryUsers} id = ", "campaign.name" to params[1]),
                    upd.callbackQuery.message.messageId,
                    fromId(upd)
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_USERS_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            else errorAnswer.invoke()
            CAMPAIGN_FOR_SURVEY -> if (userStates[upd.callbackQuery.from.id]?.state == CAMPAIGN_FOR_SURVEY) try {
                val campaign = if (params[1].endsWith("common"))
                    service.getCampaignById(params[1].split("\\s+".toRegex())[0].toLong())
                        ?: throw CampaignNotFoundException()
                else
                    service.getCampaignById(params[1].toLong())
                        ?: throw CampaignNotFoundException()

                userStates[upd.callbackQuery.from.id] =
                    UserData(CAMPAIGN_FOR_SURVEY, upd.callbackQuery.from, campaign = campaign)

                val surveys = service.getSurveyByCampaign(campaign)
                showSurveys(surveys.toList(), upd)
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SURVEY execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            else errorAnswer.invoke()
            MAIN_MENU_ADD_ADMIN, MAIN_MENU_ADD_GROUP, MAIN_MENU_DELETE_ADMIN, MAIN_MENU_DELETE_GROUP -> try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()

                userStates[upd.callbackQuery.from.id] =
                    UserData(callBackCommand, upd.callbackQuery.from, campaign = campaign)
                execute(callbackAnswer.also {
                    it.text = when (callBackCommand) {
                        MAIN_MENU_ADD_ADMIN -> text.clbAddAdminToCampaign
                        MAIN_MENU_DELETE_ADMIN -> text.clbDeleteAdminFromCampaign
                        MAIN_MENU_ADD_GROUP -> text.clbAddGroupToCampaign
                        MAIN_MENU_DELETE_GROUP -> text.clbDeleteGroupFromCampaign
                        else -> throw CommandNotFoundException()
                    }
                })
                deleteMessage(upd.callbackQuery.message)
                sendMessage(
                    msgBackMenu(
                        when (callBackCommand) {
                            MAIN_MENU_ADD_ADMIN -> text.msgAdminToCampaignAdminId
                            MAIN_MENU_DELETE_ADMIN -> text.msgAdminDeleteFromCampaignAdminId
                            MAIN_MENU_ADD_GROUP -> text.msgGroupToCampaignGroupId
                            MAIN_MENU_DELETE_GROUP -> text.msgGroupDeleteFromCampaignGroupId
                            else -> throw CommandNotFoundException()
                        }, text.back
                    ), fromId(upd)
                )
            } catch (t: Throwable) {
                log.error("$callBackCommand execute error", t)
                execute(callbackAnswer.also {
                    it.text = when (callBackCommand) {
                        MAIN_MENU_ADD_ADMIN -> text.errClbAddAdminToCampaign
                        MAIN_MENU_DELETE_ADMIN -> text.errClbDeleteAdminFromCampaign
                        MAIN_MENU_ADD_GROUP -> text.errClbAddGroupFromCampaign
                        MAIN_MENU_DELETE_GROUP -> text.errClbDeleteGroupFromCampaign
                        else -> throw CommandNotFoundException()
                    }
                })
                throw t
            }
            else -> errorAnswer.invoke()
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
                    fromId(upd)
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
                    fromId(upd)
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
                    fromId(upd)
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
                    fromId(upd)
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
                    fromId(upd)
                )
            } catch (t: Throwable) {
                log.error("CAMPAIGN_FOR_SEND_USERS_MSG execute error", t)
                execute(callbackAnswer.also { it.text = text.errClbSendMessageToEveryUsers })
                throw t
            }
            MAIN_MENU_ADD_ADMIN == callBackCommand || MAIN_MENU_DELETE_ADMIN == callBackCommand -> try {

                val campaign = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()

                userStates[upd.callbackQuery.from.id] =
                    UserData(callBackCommand, upd.callbackQuery.from, campaign = campaign)
                execute(callbackAnswer.also {
                    it.text = when (callBackCommand) {
                        MAIN_MENU_ADD_ADMIN -> text.clbAddAdminToCampaign
                        else -> text.clbDeleteAdminFromCampaign
                    }
                })
                deleteMessage(upd.callbackQuery.message)
                sendMessage(
                    msgBackMenu(
                        when (callBackCommand) {
                            MAIN_MENU_ADD_ADMIN -> text.msgAdminToCampaignAdminId
                            else -> text.msgAdminDeleteFromCampaignAdminId
                        }, text.back
                    ), fromId(upd)
                )
            } catch (t: Throwable) {
                log.error("$callBackCommand execute error", t)
                execute(callbackAnswer.also {
                    it.text = when (callBackCommand) {
                        MAIN_MENU_ADD_ADMIN -> text.errClbAddAdminToCampaign
                        else -> text.errClbDeleteAdminFromCampaign
                    }
                })
                throw t
            }
            else -> {
                setTextToMessage(
                    resourceText(text.errCommon),
                    upd.callbackQuery.message.messageId,
                    fromId(upd)
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.errClbCommon })
            }
        }
    }

    private fun doUserCallback(upd: Update) {
        val params = upd.callbackQuery.data.split("\\s+".toRegex())
        val callbackAnswer = AnswerCallbackQuery()
        val callBackCommand: UserState
        callbackAnswer.callbackQueryId = upd.callbackQuery.id

        try {
            callBackCommand = UserState.valueOf(params[0])
        } catch (e: Exception) {
            log.error("UserState = \"${upd.callbackQuery.data}\", not found", e)
            execute(callbackAnswer.also { it.text = text.errClbUser })
            throw e
        }

        val timeOutBack = {
            execute(callbackAnswer.also { it.text = text.clbSurveyTimeOut })
            deleteMessage(upd.callbackQuery.message)
            sendMessage(mainUsersMenu(text, text.errUsers), fromId(upd))
        }


        when (callBackCommand) {
            JOIN_TO_CAMPAIGN -> {
                val userChats = getAllUserChats(service.getAllGroups().toList(), message(upd).from.id)

                execute(callbackAnswer.also { it.text = "todo clb camp search" /* todo clb camp search */ })

                if (userChats.isNotEmpty()) {
                    val availableCampaigns = service.getAllCampaignsByChatListNotContainsUser(
                        userChats.map { it.groupId },
                        message(upd).from.id
                    ).toList()

                    if (availableCampaigns.isNotEmpty()) {
                        editMessage(
                            message(upd),
                            userJoinToCampaigns(text, availableCampaigns, text.userAvailableCampaigns)
                        )
                        userStates[message(upd).from.id] = UserData(JOIN_TO_CAMPAIGN_MENU, message(upd).from)
                    } else editMessage(
                        message(upd),
                        userCampaignsMenu(
                            text,
                            service.getAllCampaignByUserId(message(upd).from.id),
                            text.msgUserAvailableCampaignsNotFound
                        )
                    )
                } else editMessage(
                        message(upd),
                        userJoinToCampaigns(text, emptyList(), text.inviteText)
                    )
            }
            JOIN_TO_CAMPAIGN_BACK -> TODO("JOIN_TO_CAMPAIGN_BACK implement")
            JOIN_TO_CAMPAIGN_MENU -> {
                val campaignForAdd = service.getCampaignById(params[1].toLong()) ?: throw CampaignNotFoundException()
                execute(callbackAnswer.also { it.text = text.clbUserAddedToCampaign })
                service.getUserById(upd.callbackQuery.from.id)?.let {
                    service.createOrUpdateGroupUser(
                        UserInCampaign(
                            upd.callbackQuery.from.id,
                            createDate = now(),
                            firstName = upd.callbackQuery.from.firstName,
                            lastName = upd.callbackQuery.from.lastName,
                            userName = upd.callbackQuery.from.userName,
                            campaigns = it.campaigns.toHashSet().apply { add(campaignForAdd) }
                        )
                    )
                } ?: {
                    service.createOrUpdateGroupUser(
                        UserInCampaign(
                            upd.callbackQuery.from.id,
                            createDate = now(),
                            firstName = upd.callbackQuery.from.firstName,
                            lastName = upd.callbackQuery.from.lastName,
                            userName = upd.callbackQuery.from.userName,
                            campaigns = hashSetOf(campaignForAdd)
                        )
                    )
                }.invoke()
                setTextToMessage(
                    text.userAddedToCampaign,
                    upd.callbackQuery.message.messageId,
                    fromId(upd)
                )
            }
            USER_MENU_ACTIVE_CAMPAIGN_SELECT -> {
                if (userStates[upd.callbackQuery.from.id]?.state == USER_MENU_ACTIVE_CAMPAIGN) {
                    execute(callbackAnswer.also { it.text = text.clbSurveyCollectProcess })
                    val tasks = service.getAllSurveyForUser(params[1].toLong(), upd.callbackQuery.from.id).toList()
                    if (tasks.isNotEmpty())
                        editMessage(
                            upd.callbackQuery.message,
                            msgTaskList(
                                text.sendChooseTask,
                                CHOOSE_TASK.toString(),
                                tasks
                            )
                        )
                    else sendMessage(mainUsersMenu(text, text.userTaskNotFound), fromId(upd))
                } else timeOutBack.invoke()
            }
            USER_MENU_ACTIVE_COMMON_CAMPAIGN_SELECT -> {
                if (userStates[upd.callbackQuery.from.id]?.state == USER_MENU_ACTIVE_CAMPAIGN) {
                    execute(callbackAnswer.also { it.text = text.clbSurveyCollectProcess })

                    val tasks = service.getAllSurveysByUserFromCampaigns(upd.callbackQuery.from.id, true).toList()

                    if (tasks.isNotEmpty())
                        editMessage(
                            upd.callbackQuery.message,
                            msgTaskList(
                                text.sendChooseTask,
                                CHOOSE_TASK.toString(),
                                tasks
                            )
                        )
                    else sendMessage(mainUsersMenu(text, text.userTaskNotFound), fromId(upd))
                } else timeOutBack.invoke()
            }
            CHOOSE_TASK -> {
                if (userStates[upd.callbackQuery.from.id]?.state == USER_MENU_ACTIVE_CAMPAIGN) {
                    val survey = service.getSurveyById(params[1].toLong()) ?: throw SurveyNotFoundException()

                    userStates[upd.callbackQuery.from.id]!!.apply {
                        this.survey = survey
                        this.surveyInProgress = SurveyDAO(
                            id = survey.id!!,
                            name = survey.name,
                            description = survey.description,
                            createDate = survey.createDate,
                            questions = survey.questions.toList().sortedBy { it.sortPoints },
                            state = 0
                        )
                    }

                    editMessage(
                        upd.callbackQuery.message,
                        msgQuestion(
                            text,
                            userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!,
                            "$SURVEY_USERS_ANSWER"
                        )
                    )
                } else timeOutBack.invoke()
            }
            SURVEY_USERS_ANSWER -> {
                if (userStates[upd.callbackQuery.from.id]?.state == USER_MENU_ACTIVE_CAMPAIGN) {
                    val prevState = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.state
                    val question = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.questions[prevState]
                    val prevAnswer = question.options.first { it.id == params[1].toLong() }

                    userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.apply {
                        if (correct) correct = prevAnswer.correct
                        currentValue += prevAnswer.value
                        state++
                    }

                    if (userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.let { it.state < it.questions.size })
                        editMessage(
                            upd.callbackQuery.message,
                            msgQuestion(
                                text,
                                userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!,
                                "$SURVEY_USERS_ANSWER"
                            )
                        )
                    else {
                        if (userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.correct) {
                            service.getUserById(upd.callbackQuery.from.id)?.let {
                                service.savePassedSurvey(
                                    PassedSurvey(
                                        value = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.currentValue,
                                        description = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.description,
                                        passDate = now(),
                                        survey = userStates[upd.callbackQuery.from.id]!!.survey!!,
                                        user = it
                                    )
                                )
                            } ?: {
                                val user = service.createOrUpdateGroupUser(
                                    UserInCampaign(
                                        userId = upd.callbackQuery.from.id,
                                        firstName = upd.callbackQuery.from.firstName,
                                        lastName = upd.callbackQuery.from.lastName,
                                        userName = upd.callbackQuery.from.userName,
                                        createDate = now(),
                                        campaigns = emptySet()
                                    )
                                )
                                service.savePassedSurvey(
                                    PassedSurvey(
                                        value = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.currentValue,
                                        description = userStates[upd.callbackQuery.from.id]!!.surveyInProgress!!.description,
                                        passDate = now(),
                                        survey = userStates[upd.callbackQuery.from.id]!!.survey!!,
                                        user = user
                                    )
                                )
                            }.invoke()
                            editMessage(
                                upd.callbackQuery.message, userCampaignsMenu(
                                    text,
                                    service.getAllCampaignByUserId(upd.callbackQuery.from.id),
                                    text.msgUserTaskPassed
                                )
                            )
                        } else
                            editMessage(
                                upd.callbackQuery.message, userCampaignsMenu(
                                    text,
                                    service.getAllCampaignByUserId(upd.callbackQuery.from.id),
                                    text.msgUserTaskFailed
                                )
                            )
                    }
                } else timeOutBack.invoke()
            }
            RESET -> {
                deleteMessage(upd.callbackQuery.message)
                sendMessage(mainUsersMenu(text), fromId(upd))
            }
            else -> {
                setTextToMessage(
                    text.errUserUnknownCommand,
                    upd.callbackQuery.message.messageId,
                    fromId(upd)
                )
                userStates.remove(upd.callbackQuery.from.id)
                execute(callbackAnswer.also { it.text = text.clbUserUnknownCommand })
            }
        }
    }

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

    private fun sendMessage(messageText: String, chatId: Long) = try {
        val message = SendMessage().setChatId(chatId)
        log.debug("Send to fromId = $chatId\nMessage: \"$messageText\"")
        message.text = messageText
        execute(message)
    } catch (e: Exception) {
        log.warn(e.message, e)
    }

    private fun sendMessage(message: SendMessage, chatId: Long) = try {
        log.debug("Send to fromId = $chatId\nMessage: \"${message.text}\"")
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
                fromId(upd),
                message(upd).messageId
            )
        )
    }

    private fun msgToUsers(users: Iterable<UserInCampaign>, upd: Update) = users.forEach {
        execute(
            ForwardMessage(
                it.userId.toLong(),
                fromId(upd),
                message(upd).messageId
            )
        )
    }

    private fun showSurveys(surveys: List<Survey>, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = text.editSurveys
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                surveys.sortedBy { it.name }.forEach {
                    keyboard.add(
                        listOf(
                            InlineKeyboardButton().setText(it.name.subStr(25, "..."))
                                .setCallbackData("$SURVEY_EDIT ${it.id}")
                        )
                    )
                }
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyCreate)
                            .setCallbackData("$SURVEY_CREATE")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyBack)
                            .setCallbackData("$SURVEY_BACK")
                    )
                )
            }
        }
    })

    private fun editSurvey(survey: Survey, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = "$survey\n${printQuestions(survey.questions)}"
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
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
                        InlineKeyboardButton().setText(text.saveSurvey)
                            .setCallbackData("$SURVEY_SAVE")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyDelete)
                            .setCallbackData("$SURVEY_DELETE ${survey.id}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.backSurvey)
                            .setCallbackData("$SURVEY_BACK")
                    )
                )
            }
        }
    })

    private fun showQuestions(survey: Survey, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = "${survey.name}\n${survey.description}\n${printQuestions(survey.questions)}"
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                survey.questions.toList().sortedBy { it.sortPoints }.forEach {
                    keyboard.add(
                        listOf(
                            InlineKeyboardButton().setText(it.text.subStr(25, "..."))
                                .setCallbackData("$SURVEY_QUESTION_SELECT ${it.text.hashCode()}")
                        )
                    )
                }
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionCreate)
                            .setCallbackData("$SURVEY_QUESTION_CREATE")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionSelectBack)
                            .setCallbackData("$SURVEY")
                    )
                )
            }
        }
    })

    private fun editQuestion(question: Question, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = "$question\n${printOptions(question.options)}"
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionEditText)
                            .setCallbackData("$SURVEY_QUESTION_EDIT_TEXT")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionEditSort)
                            .setCallbackData("$SURVEY_QUESTION_EDIT_SORT")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionEditOptions)
                            .setCallbackData("$SURVEY_OPTIONS")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionDelete)
                            .setCallbackData("$SURVEY_QUESTION_DELETE")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyQuestionBack)
                            .setCallbackData("$SURVEY_QUESTIONS")
                    )
                )
            }
        }
    })

    private fun showOptions(question: Question, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = if (!question.options.isEmpty())
            printOptions(question.options)
        else
            "NULL"
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                question.options.toList().sortedBy { it.sortPoints }.forEach {
                    keyboard.add(
                        listOf(
                            InlineKeyboardButton().setText(it.text.subStr(25, "..."))
                                .setCallbackData("$SURVEY_OPTION_SELECT ${it.text.hashCode()}")
                        )
                    )
                }
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionCreate)
                            .setCallbackData("$SURVEY_OPTION_CREATE")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionSelectBack)
                            .setCallbackData("$SURVEY_OPTION_SELECT_BACK")
                    )
                )
            }
        }
    })

    private fun editOption(option: Option, upd: Update) = editMessage(EditMessageText().also { msg ->
        msg.chatId = fromId(upd).toString()
        msg.messageId = upd.callbackQuery.message.messageId
        msg.text = printOptions(setOf(option))
        msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
            markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                keyboard.addElements(
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionEditText)
                            .setCallbackData("$SURVEY_OPTION_EDIT_TEXT")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionEditSort)
                            .setCallbackData("$SURVEY_OPTION_EDIT_SORT")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionEditValue)
                            .setCallbackData("$SURVEY_OPTION_EDIT_CORRECT")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionDelete)
                            .setCallbackData("$SURVEY_OPTION_DELETE ${option.text.hashCode()}")
                    ),
                    listOf(
                        InlineKeyboardButton().setText(text.surveyOptionBack)
                            .setCallbackData("$SURVEY_OPTIONS")
                    )
                )
            }
        }
    })

    private fun sendTable(chatId: Long, excelEntities: Iterable<ExcelEntity>, entityName: String = "") = try {
        excelEntities.firstOrNull()?.let {
            val file = File(
                "tableFiles/${entityName}_" +
                        convertTime(System.currentTimeMillis(), SimpleDateFormat("yyyy_MM_dd__HH-mm-ss")) + ".xls"
            )

            writeIntoExcel(file, excelEntities)

            val dir = File("tmpDir")

            dir.listFiles().forEach {
                if (it.delete()) log.info("Remove file from ${dir.name}")
                else log.warn("File not remove from ${dir.name}")
            }

            val src = FileInputStream(file).channel
            val resultFile = File(
                "${dir.name}/${resourceText(
                    text.fileNameTextTmp,
                    "file.name" to entityName,
                    "file.time" to convertTime(System.currentTimeMillis(), SimpleDateFormat("yyyy_MM_dd__HH-mm-ss"))
                )}"
            )

            FileOutputStream(resultFile).channel.transferFrom(src, 0, src.size())

            val sendDocumentRequest = SendDocument()
            sendDocumentRequest.setChatId(chatId)
            sendDocumentRequest.setDocument(resultFile)
            execute(sendDocumentRequest)
        } ?: {
            sendMessage(
                resourceText(text.msgDataInTableNotExist, "table.name" to entityName), chatId
            )
        }.invoke()
    } catch (e: Exception) {
        log.warn("Can't send file with list of participants", e)
    }

    private fun enterText(message: Message, text: String, textBack: String, stateBack: UserState) =
        editMessage(EditMessageText().also { msg ->
            msg.chatId = message.chatId.toString()
            msg.messageId = message.messageId
            msg.text = text
            msg.replyMarkup = InlineKeyboardMarkup().also { markup ->
                markup.keyboard = ArrayList<List<InlineKeyboardButton>>().also { keyboard ->
                    keyboard.addElements(
                        listOf(
                            InlineKeyboardButton().setText(textBack)
                                .setCallbackData("$stateBack")
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

    private fun editMessage(old: Message, new: SendMessage) = try {
        execute(EditMessageText().also { msg ->
            msg.chatId = old.chatId.toString()
            msg.messageId = old.messageId
            msg.text = new.text
            msg.replyMarkup = new.replyMarkup as InlineKeyboardMarkup
        })
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
        userStates[message(upd).from?.id ?: upd.callbackQuery.from.id]!!.state = NONE
        menu.invoke(message(upd), msgTest)
    }

    private fun getUser(chatId: Long, userId: Int) = execute(GetChatMember().setChatId(chatId).setUserId(userId))

    override fun getBotUsername(): String = botUsername

    override fun getBotToken(): String = botToken

    private fun fromId(upd: Update) = upd.message?.chatId ?: upd.callbackQuery!!.message!!.chatId!!
    private fun message(upd: Update) = upd.message ?: upd.callbackQuery!!.message!!
}
