package com.example.spheresweeper

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.spheresweeper.data.GamePreferences
import com.example.spheresweeper.ui.CampaignScreen
import com.example.spheresweeper.ui.GameScreen
import com.example.spheresweeper.ui.GameViewModel
import com.example.spheresweeper.ui.MenuScreen
import com.example.spheresweeper.ui.Screen
import com.example.spheresweeper.ui.theme.GameTheme
import com.example.spheresweeper.ui.theme.GameThemes
import com.example.spheresweeper.ui.theme.SphereSweeperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars from the first frame, with icons matching the stored theme.
        applySystemBars(GameThemes.byId(GamePreferences(this).themeId))
        super.onCreate(savedInstanceState)
        setContent {
            val vm: GameViewModel = viewModel()
            val theme = vm.theme
            DisposableEffect(theme) {
                applySystemBars(theme)
                onDispose { }
            }
            SphereSweeperTheme(theme) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(theme.ui.backgroundBottom),
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

    /**
     * Edge-to-edge with transparent bars: light icons over dark themes, dark icons over light ones.
     * Navigation bars that cannot show dark icons (API 24 and 25) get a translucent dark scrim instead.
     * The window background follows the theme so no other colour shows behind the content. From
     * Android 13 the theme's window style also becomes the splash screen theme, so the next cold start
     * opens in the theme's colour; older versions start in the manifest theme's colour.
     */
    private fun applySystemBars(theme: GameTheme) {
        if (theme.dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, LEGACY_NAVIGATION_SCRIM),
            )
        }
        window.setBackgroundDrawable(ColorDrawable(theme.ui.windowBackground.toArgb()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            splashScreen.setSplashScreenTheme(theme.windowStyle)
        }
    }

    private companion object {
        /** Scrim of the light navigation bar where its icons stay light; the activity library's default. */
        val LEGACY_NAVIGATION_SCRIM: Int = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
