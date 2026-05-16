package ru.sipaha.spkremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.sipaha.spkremote.app.ui.App
import ru.sipaha.spkremote.app.ui.theme.SpkRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpkRemoteTheme {
                App()
            }
        }
    }
}
