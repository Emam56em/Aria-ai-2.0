package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.core.di.AppContainer
import com.example.core.localization.AppLanguage
import com.example.core.localization.LocalAppStrings
import com.example.core.localization.stringsFor
import com.example.data.ai.configureAppCheck
import com.example.ui.screens.AllAppsScreen
import com.example.ui.screens.AlwaysOnVoiceScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.DeviceControlsScreen
import com.example.ui.screens.PermissionsDashboardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AriaTheme
import com.example.ui.viewmodel.AriaViewModel

object AriaRoutes {
    const val CHAT = "chat"
    const val CONTROLS = "controls"
    const val ALL_APPS = "all_apps"
    const val ALWAYS_ON_VOICE = "always_on_voice"
    const val PERMISSIONS = "permissions"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { AppContainer.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before any UI or ViewModels.
        configureAppCheck(this, intent)

        // STEP 2: Enable dark-mode edge-to-edge display.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            val ariaViewModel: AriaViewModel = viewModel(
                factory = AriaViewModel.factory(applicationContext, appContainer)
            )
            AriaApp(viewModel = ariaViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.audioSessionManager.setAppForegroundState(true)
        appContainer.audioSessionManager.refreshEnvironmentState()
    }

    override fun onStop() {
        appContainer.audioSessionManager.setAppForegroundState(false)
        super.onStop()
    }
}

@Composable
fun AriaApp(viewModel: AriaViewModel) {
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val flashlightState by viewModel.flashlightState.collectAsStateWithLifecycle()
    val supportedApps by viewModel.supportedApps.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredInstalledApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val searchQuery by viewModel.allAppsSearchQuery.collectAsStateWithLifecycle()
    val appFilter by viewModel.allAppsFilter.collectAsStateWithLifecycle()
    val accessibilityState by viewModel.accessibilityState.collectAsStateWithLifecycle()
    val voiceEvent by viewModel.voiceEvents.collectAsStateWithLifecycle()
    val liveSessionState by viewModel.liveSessionState.collectAsStateWithLifecycle()
    val liveConversationState by viewModel.liveConversationState.collectAsStateWithLifecycle()
    val voiceMonitorState by viewModel.voiceMonitorState.collectAsStateWithLifecycle()
    val speakingMessageId by viewModel.speakingMessageId.collectAsStateWithLifecycle()
    val statusNotice by viewModel.statusNotice.collectAsStateWithLifecycle()

    val isBengali = preferences.language == AppLanguage.BENGALI
    val strings = stringsFor(preferences.language)
    val navController = rememberNavController()

    CompositionLocalProvider(LocalAppStrings provides strings) {
        AriaTheme(isBengali = isBengali) {
            NavHost(
                navController = navController,
                startDestination = AriaRoutes.CHAT
            ) {
                composable(AriaRoutes.CHAT) {
                    ChatScreen(
                        language = preferences.language,
                        conversations = conversations,
                        activeConversationId = activeConversationId,
                        messages = messages,
                        inputDraft = inputDraft,
                        generationState = generationState,
                        voiceEvent = voiceEvent,
                        liveSessionState = liveSessionState,
                        liveConversationState = liveConversationState,
                        voiceMonitorState = voiceMonitorState,
                        speakingMessageId = speakingMessageId,
                        statusNotice = statusNotice,
                        onUpdateDraft = viewModel::updateInputDraft,
                        onSendMessage = viewModel::sendMessage,
                        onRetryFailed = viewModel::retryLastFailed,
                        onDismissError = viewModel::dismissError,
                        onSelectConversation = viewModel::selectConversation,
                        onNewChat = viewModel::startNewChat,
                        onDeleteConversation = viewModel::deleteConversation,
                        onToggleLanguage = viewModel::toggleLanguage,
                        onToggleSpeakMessage = viewModel::toggleSpeakMessage,
                        onExecuteActionTag = viewModel::executeActionTag,
                        onStartLiveConversation = viewModel::startLiveConversation,
                        onEndLiveConversation = viewModel::endLiveConversation,
                        onToggleLiveMute = viewModel::toggleLiveMute,
                        onStartVoiceListening = viewModel::startVoiceListening,
                        onStopVoiceListening = viewModel::stopVoiceListening,
                        onResetVoiceSession = viewModel::resetVoiceSession,
                        onRefreshLiveCapabilities = viewModel::refreshLiveCapabilities,
                        onStopAlwaysOn = viewModel::stopAlwaysOnListening,
                        onResolveSensitiveAction = viewModel::resolveSensitiveAction,
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onNavigateControls = {
                            viewModel.refreshDeviceControls()
                            navController.navigate(AriaRoutes.CONTROLS)
                        },
                        onNavigateAllApps = {
                            viewModel.refreshInstalledApps()
                            navController.navigate(AriaRoutes.ALL_APPS)
                        },
                        onNavigateAlwaysOn = {
                            viewModel.refreshVoiceMonitorEnvironment()
                            navController.navigate(AriaRoutes.ALWAYS_ON_VOICE)
                        },
                        onNavigatePermissions = {
                            viewModel.refreshAccessibilityStatus()
                            navController.navigate(AriaRoutes.PERMISSIONS)
                        },
                        onNavigateSettings = {
                            navController.navigate(AriaRoutes.SETTINGS)
                        }
                    )
                }

                composable(AriaRoutes.CONTROLS) {
                    DeviceControlsScreen(
                        flashlightState = flashlightState,
                        supportedApps = supportedApps,
                        installedAppsCount = installedApps.size,
                        statusNotice = statusNotice,
                        onToggleFlashlight = viewModel::toggleFlashlight,
                        onLaunchApp = viewModel::launchSupportedApp,
                        onOpenAllApps = {
                            viewModel.refreshInstalledApps()
                            navController.navigate(AriaRoutes.ALL_APPS)
                        },
                        onOpenPermissions = {
                            viewModel.refreshAccessibilityStatus()
                            navController.navigate(AriaRoutes.PERMISSIONS)
                        },
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AriaRoutes.ALL_APPS) {
                    AllAppsScreen(
                        apps = filteredApps,
                        totalDetectedCount = installedApps.size,
                        searchQuery = searchQuery,
                        selectedFilter = appFilter,
                        isLoading = isLoadingApps,
                        statusNotice = statusNotice,
                        onSearchQueryChange = viewModel::updateAllAppsSearchQuery,
                        onFilterChange = viewModel::updateAllAppsFilter,
                        onRefresh = viewModel::refreshInstalledApps,
                        onLaunchApp = viewModel::launchInstalledApp,
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AriaRoutes.ALWAYS_ON_VOICE) {
                    AlwaysOnVoiceScreen(
                        preferences = preferences,
                        monitorState = voiceMonitorState,
                        statusNotice = statusNotice,
                        onStartListening = viewModel::startAlwaysOnListening,
                        onPauseListening = viewModel::pauseAlwaysOnListening,
                        onResumeListening = viewModel::resumeAlwaysOnListening,
                        onStopListening = viewModel::stopAlwaysOnListening,
                        onRestartService = viewModel::restartAlwaysOnListening,
                        onSaveWakeWord = viewModel::updateWakeWord,
                        onSelectLanguage = viewModel::setLanguage,
                        onToggleVoiceResponse = viewModel::setAlwaysOnVoiceResponse,
                        onToggleReturnToMonitoring = viewModel::setResumeMonitoringAfterReply,
                        onRefreshEnvironment = viewModel::refreshVoiceMonitorEnvironment,
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AriaRoutes.PERMISSIONS) {
                    PermissionsDashboardScreen(
                        detectedAppsCount = installedApps.size,
                        flashlightState = flashlightState,
                        accessibilityState = accessibilityState,
                        statusNotice = statusNotice,
                        onRefreshAccessibilityStatus = viewModel::refreshAccessibilityStatus,
                        onTriggerAccessibilityAction = viewModel::triggerAuthorizedAccessibilityAction,
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(AriaRoutes.SETTINGS) {
                    SettingsScreen(
                        preferences = preferences,
                        statusNotice = statusNotice,
                        onSelectLanguage = viewModel::setLanguage,
                        onSelectModel = viewModel::setModel,
                        onToggleAutoSpeak = viewModel::setAutoSpeakReplies,
                        onOpenAlwaysOnSettings = {
                            viewModel.refreshVoiceMonitorEnvironment()
                            navController.navigate(AriaRoutes.ALWAYS_ON_VOICE)
                        },
                        onClearAllHistory = viewModel::clearAllHistory,
                        onClearStatusNotice = viewModel::clearStatusNotice,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
