package com.sleepcare.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sleepcare.mobile.domain.DrowsinessRepository
import com.sleepcare.mobile.domain.ExamScheduleRepository
import com.sleepcare.mobile.domain.RecommendationRepository
import com.sleepcare.mobile.domain.SettingsRepository
import com.sleepcare.mobile.domain.SleepRepository
import com.sleepcare.mobile.domain.StudyPlanRepository
import com.sleepcare.mobile.ui.analysis.AnalysisHubScreen
import com.sleepcare.mobile.ui.analysis.AnalysisViewModel
import com.sleepcare.mobile.ui.analysis.DrowsinessAnalysisDetailScreen
import com.sleepcare.mobile.ui.analysis.SleepAnalysisDetailScreen
import com.sleepcare.mobile.ui.components.AppBottomBar
import com.sleepcare.mobile.ui.devices.DeviceConnectionScreen
import com.sleepcare.mobile.ui.devices.DevicesViewModel
import com.sleepcare.mobile.ui.home.HomeScreen
import com.sleepcare.mobile.ui.home.HomeViewModel
import com.sleepcare.mobile.ui.onboarding.OnboardingScreen
import com.sleepcare.mobile.ui.onboarding.OnboardingViewModel
import com.sleepcare.mobile.ui.schedule.ExamScheduleScreen
import com.sleepcare.mobile.ui.schedule.ScheduleViewModel
import com.sleepcare.mobile.ui.schedule.SleepScheduleSuggestionScreen
import com.sleepcare.mobile.ui.schedule.StudyPlanScreen
import com.sleepcare.mobile.ui.settings.SettingsScreen
import com.sleepcare.mobile.ui.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object AppRoute {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Analysis = "analysis"
    const val SleepAnalysisDetail = "sleep-analysis-detail"
    const val DrowsinessAnalysisDetail = "drowsiness-analysis-detail"
    const val Devices = "devices"
    const val Schedule = "schedule"
    const val StudyPlan = "study-plan"
    const val ExamSchedule = "exam-schedule"
    const val Settings = "settings"
}

data class AppUiState(
    val onboardingCompleted: Boolean? = null,
)

@Composable
fun SleepCareApp(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val appState by appViewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentRootRoute = currentBackStackEntry?.destination?.rootRoute()

    if (appState.onboardingCompleted == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute != AppRoute.Onboarding) {
                AppBottomBar(
                    currentRoute = currentRootRoute ?: AppRoute.Home,
                    onRouteSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(AppRoute.Home) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = if (appState.onboardingCompleted == true) AppRoute.Home else AppRoute.Onboarding,
        ) {
            composable(AppRoute.Onboarding) {
                val viewModel: OnboardingViewModel = hiltViewModel()
                OnboardingScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onStartClick = {
                        navController.navigate(AppRoute.Home) {
                            popUpTo(AppRoute.Onboarding) { inclusive = true }
                        }
                    },
                )
            }
            composable(AppRoute.Home) {
                val viewModel: HomeViewModel = hiltViewModel()
                HomeScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onOpenAnalysis = { navController.navigate(AppRoute.Analysis) },
                    onOpenSchedule = { navController.navigate(AppRoute.Schedule) },
                )
            }
            composable(AppRoute.Analysis) {
                val viewModel: AnalysisViewModel = hiltViewModel()
                AnalysisHubScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onOpenSleepDetail = { navController.navigate(AppRoute.SleepAnalysisDetail) },
                    onOpenDrowsinessDetail = { navController.navigate(AppRoute.DrowsinessAnalysisDetail) },
                )
            }
            composable(AppRoute.SleepAnalysisDetail) {
                val viewModel: AnalysisViewModel = hiltViewModel()
                SleepAnalysisDetailScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenSchedule = { navController.navigate(AppRoute.Schedule) },
                )
            }
            composable(AppRoute.DrowsinessAnalysisDetail) {
                val viewModel: AnalysisViewModel = hiltViewModel()
                DrowsinessAnalysisDetailScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(AppRoute.Devices) {
                val viewModel: DevicesViewModel = hiltViewModel()
                DeviceConnectionScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                )
            }
            composable(AppRoute.Schedule) {
                val viewModel: ScheduleViewModel = hiltViewModel()
                SleepScheduleSuggestionScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onOpenStudyPlan = { navController.navigate(AppRoute.StudyPlan) },
                    onOpenExamSchedule = { navController.navigate(AppRoute.ExamSchedule) },
                )
            }
            composable(AppRoute.StudyPlan) {
                val viewModel: ScheduleViewModel = hiltViewModel()
                StudyPlanScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        appViewModel.refreshRecommendations()
                        appViewModel.showMessage(snackbarHostState, "학습 플랜을 저장했습니다.")
                    },
                )
            }
            composable(AppRoute.ExamSchedule) {
                val viewModel: ScheduleViewModel = hiltViewModel()
                ExamScheduleScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onChanged = {
                        appViewModel.refreshRecommendations()
                        appViewModel.showMessage(snackbarHostState, "시험 일정을 반영했습니다.")
                    },
                )
            }
            composable(AppRoute.Settings) {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    paddingValues = paddingValues,
                    viewModel = viewModel,
                    onOpenDevices = { navController.navigate(AppRoute.Devices) },
                    onResetCompleted = {
                        appViewModel.resetAndReSeed()
                        appViewModel.showMessage(snackbarHostState, "앱 데이터를 초기화하고 샘플 데이터를 다시 불러왔습니다.")
                        navController.navigate(AppRoute.Onboarding) {
                            popUpTo(AppRoute.Home) { inclusive = true }
                        }
                    },
                )
            }
        }
    }

    LaunchedEffect(appState.onboardingCompleted, currentRoute) {
        if (appState.onboardingCompleted == true && currentRoute == AppRoute.Onboarding) {
            navController.navigate(AppRoute.Home) {
                popUpTo(AppRoute.Onboarding) { inclusive = true }
            }
        }
    }
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sleepRepository: SleepRepository,
    private val drowsinessRepository: DrowsinessRepository,
    private val studyPlanRepository: StudyPlanRepository,
    private val examScheduleRepository: ExamScheduleRepository,
    private val recommendationRepository: RecommendationRepository,
) : ViewModel() {
    val uiState = settingsRepository.observeOnboardingState()
        .map { AppUiState(onboardingCompleted = it.completed) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppUiState(),
        )

    init {
        viewModelScope.launch {
            seedAndRefresh()
        }
    }

    fun refreshRecommendations() {
        viewModelScope.launch {
            recommendationRepository.refreshRecommendations()
        }
    }

    fun resetAndReSeed() {
        viewModelScope.launch {
            settingsRepository.resetAppData()
            seedAndRefresh()
        }
    }

    fun showMessage(snackbarHostState: SnackbarHostState, message: String) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    private suspend fun seedAndRefresh() {
        sleepRepository.seedIfEmpty()
        drowsinessRepository.seedIfEmpty()
        studyPlanRepository.seedIfEmpty()
        examScheduleRepository.seedIfEmpty()
        sleepRepository.refreshFromSource()
        drowsinessRepository.refreshFromSource()
        recommendationRepository.refreshRecommendations()
    }
}

private fun androidx.navigation.NavDestination.rootRoute(): String? {
    val routes = hierarchy.mapNotNull { it.route }
    return when {
        routes.any { it == AppRoute.Analysis || it == AppRoute.SleepAnalysisDetail || it == AppRoute.DrowsinessAnalysisDetail } -> AppRoute.Analysis
        routes.any { it == AppRoute.Schedule || it == AppRoute.StudyPlan || it == AppRoute.ExamSchedule } -> AppRoute.Schedule
        routes.any { it == AppRoute.Settings || it == AppRoute.Devices } -> AppRoute.Settings
        routes.any { it == AppRoute.Home } -> AppRoute.Home
        else -> route
    }
}
