package com.example

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DeveloperOrientation
import com.example.data.model.ThemePreference
import com.example.ui.RoadAwarenessViewModel
import com.example.ui.components.TouchTelemetry
import com.example.ui.components.ensureMapLibreInitialized
import com.example.ui.screens.RoadAwarenessScreen
import com.example.ui.theme.RoadAwarenessTheme
import com.example.ui.theme.RoadColors

class MainActivity : ComponentActivity() {
  private val viewModel: RoadAwarenessViewModel by viewModels()
  private var configRevision by mutableIntStateOf(0)

  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    TouchTelemetry.onDispatchTouchEvent(ev)
    return super.dispatchTouchEvent(ev)
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    configRevision++
  }

  @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val initialDark =
      (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val initialStyle =
      if (initialDark) SystemBarStyle.dark(Color.TRANSPARENT)
      else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = initialStyle, navigationBarStyle = initialStyle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    val initialBgColor =
      if (initialDark) RoadColors.DarkBackground.toArgb() else RoadColors.LightBackground.toArgb()
    window.setBackgroundDrawable(ColorDrawable(initialBgColor))
    ensureMapLibreInitialized(applicationContext)

    setContent {
      @Suppress("UNUSED_VARIABLE")
      val rev = configRevision
      val windowSizeClass = calculateWindowSizeClass(this)
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val sysDark = isSystemInDarkTheme()
      val useDark =
        when (uiState.settings.themePreference) {
          ThemePreference.AUTO -> sysDark
          ThemePreference.LIGHT -> false
          ThemePreference.DARK -> true
        }

      var appliedDark by remember { mutableStateOf(initialDark) }
      LaunchedEffect(useDark) {
        if (appliedDark != useDark) {
          appliedDark = useDark
          val barStyle =
            if (useDark) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
          enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
          val bgColor =
            if (useDark) RoadColors.DarkBackground.toArgb() else RoadColors.LightBackground.toArgb()
          window.setBackgroundDrawable(ColorDrawable(bgColor))
        }
      }

      if (BuildConfig.DEBUG) {
        LaunchedEffect(uiState.settings.developerOrientation) {
          val target =
            when (uiState.settings.developerOrientation) {
              DeveloperOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
              DeveloperOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              DeveloperOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
          if (requestedOrientation != target) {
            requestedOrientation = target
          }
        }
      }

      RoadAwarenessTheme(themePreference = uiState.settings.themePreference) {
        RoadAwarenessScreen(
          viewModel = viewModel,
          windowSizeClass = windowSizeClass,
        )
      }
    }
  }
}
