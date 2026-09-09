package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.screens.LoginGateScreen
import com.example.ui.screens.MainAgentScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val entryMode by viewModel.entryMode.collectAsStateWithLifecycle()
          if (entryMode == null) {
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
