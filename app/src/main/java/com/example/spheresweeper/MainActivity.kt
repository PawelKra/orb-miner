package com.example.spheresweeper

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spheresweeper.ui.CampaignScreen
import com.example.spheresweeper.ui.GameScreen
import com.example.spheresweeper.ui.GameViewModel
import com.example.spheresweeper.ui.MenuScreen
import com.example.spheresweeper.ui.Screen
import com.example.spheresweeper.ui.theme.SpaceColors
import com.example.spheresweeper.ui.theme.SphereSweeperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark scheme with transparent bars: light system icons over the space background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SphereSweeperTheme {
                val vm: GameViewModel = viewModel()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SpaceColors.SpaceBottom),
                ) {
                    Crossfade(
                        targetState = vm.screen,
                        animationSpec = tween(durationMillis = 280),
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.MENU -> MenuScreen(vm)
                            Screen.CAMPAIGN -> CampaignScreen(vm)
                            Screen.GAME -> GameScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
