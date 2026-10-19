package com.slickstream.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CastViewModel @Inject constructor(val manager: CastManager) : ViewModel() {
    val connection = manager.connection.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), manager.connectionSnapshot(),
    )
}
