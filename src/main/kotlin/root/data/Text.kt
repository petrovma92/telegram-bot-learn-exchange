package root.data

import kotlinx.serialization.*

@Serializable
data class Text(
    val mainMenu: String,
    val back: String,
    val mainMenuAdd: String,
    val mainMenuDelete: String,
    val mainMenuMessages: String,
    val mainMenuStatistic: String,
    val addMenu: String,
    val addMenuCampaign: String,
    val addMenuCommonCampaign: String,
    val addMenuGroup: String,
    val addMenuSurvey: String,
    val addMenuSuperAdmin: String,
    val addMenuAdmin: String,
    val addMenuMission: String,
    val addMenuTask: String,
    val deleteMenu: String,
    val deleteMenuCampaign: String,
    val deleteMenuCommonCampaign: String,
    val deleteMenuGroup: String,
    val deleteMenuSurvey: String,
    val deleteMenuSuperAdmin: String,
    val deleteMenuAdmin: String,
    val deleteMenuMission: String,
    val deleteMenuTask: String,
    val messagesMenuAllGroupsCampaign: String,
    val messagesMenuAllUsers: String,
    val infoForAdmin: String,
    val sendToEveryUser: String,
    val sendToEveryGroup: String,
    val msgNoAdmin: String,
    val msgNoCampaign: String,
    val msgSendToEveryUser: String,
    val msgSendToEveryGroup: String,
    val msgNotAdmin: String,
    val msgGetStatisticTables: String,
    val msgAdminToCampaignSelectCamp: String,
    val msgGroupToCampaignSelectCamp: String,
    val msgAdminToCampaignAdminId: String,
    val msgAdminDeleteFromCampaignAdminId: String,
    val msgGroupToCampaignGroupId: String,
    val msgGroupDeleteFromCampaignGroupId: String,
    val msgGroupToCampaign: String,
    val msgCreateCampaign: String,
    val msgRemoveCampaign: String,
    val msgUserAvailableCampaignsNotFound: String,
    val msgRemoveAdminFromCampaign: String,
    val msgRemoveGroupFromCampaign: String,
    val msgAddSuperAdmin: String,
    val msgDeleteSuperAdmin: String,
    val msgSurveyActionsName: String,
    val msgSurvey: String,
    val msgSurveysTable: String,
    val msgAdminsTable: String,
    val msgUsersInCampaign: String,
    val userMainMenuStatus: String,
    val msgSuccessDeleteAdmin: String,
    val msgSuccessAddAdmin: String,
    val msgSuccessDeleteGroup: String,
    val msgSuccessAddGroup: String,
    val msgSurveyQuestionActionsText: String,
    val msgSurveyOptionActionsText: String,
    val msgSurveyQuestionActionsSort: String,
    val msgSurveyActionsDesc: String,
    val msgSurveyOptionActionsSort: String,
    val msgSurveyOptionActionsCorrect: String,
    val msgDataInTableNotExist: String,
    val msgCreateCommonCampaign: String,
    val msgRemoveCommonCampaign: String,
    val msgSelectCampaignForAdmin: String,
    val msgUserMainMenuCommonCampaignTasks: String,
    val msgUserMainMenuCampaigns: String,
    val msgUserMainMenuStatus: String,
    val msgUserMainMenuAccount: String,
    val msgUserTaskPassed: String,
    val msgUserTaskFailed: String,
    val msgEnterYourEmail: String,
    val msgEmailSaved: String,
    val btnUserMainMenuStatus: String,
    val btnUserMainMenuAccountRegistration: String,
    val btnUserMainMenuAccountRegistrationUrl: String,
    val btnUserMainMenuAccountFriends: String,
    val addAdminToCampaign: String,
    val addGroupToCampaign: String,
    val createCampaign: String,
    val removeCampaign: String,
    val timeOutTask: String,
    val showTasksList: String,
    val taskNotFound: String,
    val inviteText: String,
    val userMainMenu: String,
    val joinToCampaign: String,
    val userMainMenuCampaigns: String,
    val userMainMenuAccount: String,
    val userAvailableCampaigns: String,
    val userAddedToCampaign: String,
    val removeAdminFromCampaign: String,
    val removeGroupFromCampaign: String,
    val sucCreateCampaign: String,
    val sucCreateCommonCampaign: String,
    val sucGroupToCampaign: String,
    val sucRemoveCampaign: String,
    val sucRemoveCommonCampaign: String,
    val sucRemoveAdminFromCampaign: String,
    val sucRemoveGroupFromCampaign: String,
    val sucMsgToUsers: String,
    val sucMsgToCampaign: String,
    val adminAvailableCampaigns: String,
    val sucSendMessageToEveryGroup: String,
    val sucSendMessageToEveryUsers: String,
    val addSuperAdmin: String,
    val sucAddSuperAdmin: String,
    val removeSuperAdmin: String,
    val sucRemoveSuperAdmin: String,
    val surveyOptions: String,
    val surveyOptionCreate: String,
    val surveyDelete: String,
    val surveyDeleted: String,
    val editQuestions: String,
    val surveyOptionBack: String,
    val surveyOptionSelectBack: String,
    val surveyQuestionBack: String,
    val surveyQuestionSelectBack: String,
    val editSurveyName: String,
    val editSurveyDescription: String,
    val clbAddAdminToCampaign: String,
    val clbDeleteAdminFromCampaign: String,
    val clbAddGroupToCampaign: String,
    val clbDeleteGroupFromCampaign: String,
    val clbUserUnknownCommand: String,
    val clbUserAddedToCampaign: String,
    val clbSendMessageToEveryGroup: String,
    val clbSendMessageToEveryUsers: String,
    val clbSurveyOptions: String,
    val clbSurveyQuestions: String,
    val clbSurvey: String,
    val clbEditSurvey: String,
    val clbSurveyQuestionEdit: String,
    val clbSurveySave: String,
    val clbSurveyOptionDeleted: String,
    val clbSurveyQuestionDeleted: String,
    val clbSurveyTimeOut: String,
    val clbSurveyCollectProcess: String,
    val clbSearchCampForUser: String,
    val editSurvey: String,
    val adminAvailableCampaignsSurveys: String,
    val surveyQuestionDelete: String,
    val surveyQuestionEditOptions: String,
    val surveyQuestionEditSort: String,
    val surveyQuestionEditText: String,
    val surveyOptionDelete: String,
    val surveyOptionEditValue: String,
    val surveyOptionEditSort: String,
    val surveyOptionEditText: String,
    val saveSurvey: String,
    val backSurvey: String,
    val enterTextBack: String,
    val backToSurveyCRUDMenu: String,
    val backToSurveyMenu: String,
    val backToSurveyQuestionMenu: String,
    val surveyQuestionCreate: String,
    val backToSurveyQuestionsListMenu: String,
    val backToSurveyOptionMenu: String,
    val surveyCreate: String,
    val fileNameTextTmp: String,
    val getTableFile: String,
    val sendCampaignsTable: String,
    val sendUsersInCampaign: String,
    val sendSuperAdminTable: String,
    val sendAdminsTable: String,
    val sendSurveysTable: String,
    val sendUserInfo: String,
    val userCampaignsTask: String,
    val sendChooseTask: String,
    val userCampaignsNotFound: String,
    val userTaskNotFound: String,
    val surveyPassed: String,
    val editSurveys: String,
    val surveyBack: String,
    val survey: String,
    val reset: String,
    val errAdmins: String,
    val errUsers: String,
    val errCallback: String,
    val errDeleteAdminNotFound: String,
    val errDeleteAdminAccessDenied: String,
    val errDeleteAdmin: String,
    val errAddAdmin: String,
    val errAddAdminAccessDenied: String,
    val errAddGroup: String,
    val errAddGroupAccessDenied: String,
    val errNotFoundSurvey: String,
    val errSurveyDelete: String,
    val errSurveyEnterNumber: String,
    val errRemoveSuperAdmin: String,
    val errAddSuperAdminAlreadyExist: String,
    val errClbCommon: String,
    val errCampaignNotFound: String,
    val errAddSuperAdmin: String,
    val errClbSendMessageToEveryUsers: String,
    val errClbAddAdminToCampaign: String,
    val errClbDeleteAdminFromCampaign: String,
    val errClbDeleteGroupFromCampaign: String,
    val errClbAddGroupFromCampaign: String,
    val errDeleteGroup: String,
    val errDeleteGroupNotFound: String,
    val errDeleteGroupAccessDenied: String,
    val errCommon: String,
    val errClbSendMessageToEveryGroup: String,
    val errSendMessageToEveryGroup: String,
    val errMsgToCampaignNotFound: String,
    val errMsgToCampaign: String,
    val errMsgToUsersNotFound: String,
    val errMsgToUsers: String,
    val errAdminToCampaign: String,
    val errGroupToCampaign: String,
    val errCreateCampaign: String,
    val errCreateCampaignAlreadyExist: String,
    val errCreateCommonCampaignAlreadyExist: String,
    val errCreateCommonCampaign: String,
    val errRemoveCampaign: String,
    val errRemoveCommonCampaign: String,
    val errClbUser: String,
    val errUserUnknownCommand: String,
    val errRemoveAdminFromCampaign: String,
    val errRemoveGroupFromCampaign: String,
    val stickerHello: String,
    val lvl0: String,
    val lvl1: String,
    val lvl2: String,
    val lvl3: String,
    val lvl4: String,
    val lvl5: String,
    val lvl6: String,
    val lvl7: String
)