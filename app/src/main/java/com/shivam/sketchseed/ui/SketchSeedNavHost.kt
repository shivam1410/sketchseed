package com.shivam.sketchseed.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shivam.sketchseed.ui.daydetail.DayDetailScreen
import com.shivam.sketchseed.ui.daydetail.DayDetailViewModel
import com.shivam.sketchseed.ui.journey.JourneyScreen
import com.shivam.sketchseed.ui.settings.SettingsScreen
import com.shivam.sketchseed.ui.today.TodayScreen
import java.io.File

private object Routes {
    const val TODAY = "today"
    const val JOURNEY = "journey"
    const val SETTINGS = "settings"
    const val DAY = "day"
    const val DAY_PATTERN = "$DAY/{${DayDetailViewModel.ARG_DAY}}"

    fun day(day: Int) = "$DAY/$day"
}

@Composable
fun SketchSeedNavHost(resolvePhoto: (String) -> File?) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.TODAY) {
        composable(Routes.TODAY) {
            TodayScreen(
                onOpenJourney = { navController.navigate(Routes.JOURNEY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDay = { navController.navigate(Routes.day(it)) },
                resolvePhoto = resolvePhoto,
            )
        }

        composable(Routes.JOURNEY) {
            JourneyScreen(
                onBack = navController::popBackStack,
                onOpenDay = { navController.navigate(Routes.day(it)) },
                resolvePhoto = resolvePhoto,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.DAY_PATTERN,
            arguments = listOf(
                navArgument(DayDetailViewModel.ARG_DAY) { type = NavType.IntType },
            ),
        ) {
            DayDetailScreen(
                onBack = navController::popBackStack,
                resolvePhoto = resolvePhoto,
            )
        }
    }
}
