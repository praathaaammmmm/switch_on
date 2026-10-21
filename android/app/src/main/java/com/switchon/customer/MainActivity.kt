package com.switchon.customer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.switchon.customer.ui.App
import com.switchon.customer.ui.theme.SwitchOnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Single dark look by design: light icons on ink, regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#070606")),
        )
        super.onCreate(savedInstanceState)
        setContent { SwitchOnTheme { App() } }
    }
}
