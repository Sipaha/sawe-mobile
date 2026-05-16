package ru.sipaha.spkremote.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.sipaha.spkremote.app.ui.nav.AppNav
import ru.sipaha.spkremote.app.vm.MainViewModel

@Composable
fun App(vm: MainViewModel = viewModel()) {
    AppNav(viewModel = vm)
}
