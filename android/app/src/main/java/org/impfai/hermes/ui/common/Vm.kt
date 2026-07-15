package org.impfai.hermes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.impfai.hermes.AppContainer
import org.impfai.hermes.HermesApp

@Composable
fun rememberContainer(): AppContainer {
    val ctx = LocalContext.current
    return remember { (ctx.applicationContext as HermesApp).container }
}

/** 傷寒六經 + 附篇（config.SIX_CHANNELS 同源順序）。 */
val SIX_CHANNELS = listOf("太陽病", "陽明病", "少陽病", "太陰病", "少陰病", "厥陰病")
