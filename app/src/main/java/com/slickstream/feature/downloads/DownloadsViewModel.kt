package com.slickstream.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slickstream.core.model.Download
import com.slickstream.data.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val downloads: StateFlow<List<Download>> =
        downloadManager.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(d: Download) {
        viewModelScope.launch { downloadManager.delete(d) }
    }

    fun retry(d: Download) = downloadManager.retry(d)
}
