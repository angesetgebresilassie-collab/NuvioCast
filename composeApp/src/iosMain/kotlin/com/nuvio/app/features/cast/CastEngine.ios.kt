package com.nuvio.app.features.cast

import androidx.compose.runtime.Composable

@Composable
actual fun rememberCastEngine(): CastEngine = NoOpCastEngine
