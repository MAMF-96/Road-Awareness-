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
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.DeveloperOrientation
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
    val alwaysLightIconsBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    enableEdgeToEdge(
      statusBarStyle = alwaysLightIconsBarStyle,
      navigationBarStyle = alwaysLightIconsBarStyle,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      window.isNavigationBarContrastEnforced = false
    }
    window.setBackgroundDrawable(ColorDrawable(RoadColors.DarkBackground.toArgb()))
    ensureMapLibreInitialized(applicationContext)

    setContent {
      @Suppress("UNUSED_VARIABLE")
      val rev = configRevision
      val windowSizeClass = calculateWindowSizeClass(this)
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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

      RoadAwarenessTheme(
        themePreference = uiState.settings.themePreference,
        panelStyle = uiState.settings.panelStyle,
      ) {
        RoadAwarenessScreen(
          viewModel = viewModel,
          windowSizeClass = windowSizeClass,
        )
      }
    }
  }
}
