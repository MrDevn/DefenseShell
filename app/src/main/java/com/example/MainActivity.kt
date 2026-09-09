package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.screens.LoginGateScreen
import com.example.ui.screens.MainAgentScreen
import com.example.ui.screens.IntroScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val entryMode by viewModel.entryMode.collectAsStateWithLifecycle()
          var showIntro by remember { mutableStateOf(true) }
          LaunchedEffect(Unit) {
            delay(2400)
            showIntro = false
          }
          AnimatedContent(
            targetState = showIntro,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "intro"
          ) { introVisible ->
            if (introVisible) {
              IntroScreen()
            } else if (entryMode == null) {
              LoginGateScreen(
                onGuest = { viewModel.loginAsGuest() },
                onGitHubLogin = { token -> viewModel.loginWithGitHub(token) }
              )
            } else {
              MainAgentScreen(viewModel = viewModel)
            }
          }
        }
      }
    }
  }
}
