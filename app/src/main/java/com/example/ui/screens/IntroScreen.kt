package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun IntroScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0D0E10)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "CodeStudio",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier
        )
        Text(
            text = "создан командой Defense",
            color = Color(0xFFA6A7AD),
            fontSize = 14.sp,
            modifier = Modifier
        )
        Text(
            text = "t.me/CodeStudioDev",
            color = Color(0xFF9CA7FF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/CodeStudioDev"))
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                    }
                }
        )
    }
}
