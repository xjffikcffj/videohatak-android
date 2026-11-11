package com.videohatak.player.feature.vault

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultViewModel(
    private val repository: VaultRepository,
    private val biometricHelper: VaultBiometricHelper
) : ViewModel() {

    // AUD-01: Expose vaultVideos alias
    val vaultVideos: StateFlow<List<VaultVideo>> = repository.vaultVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val videos: StateFlow<List<VaultVideo>> = vaultVideos

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _importState = MutableStateFlow<VaultImportState>(VaultImportState.Idle)
    val importState: StateFlow<VaultImportState> = _importState.asStateFlow()

    private val _biometricError = MutableStateFlow<String?>(null)
    val biometricError: StateFlow<String?> = _biometricError.asStateFlow()

    val errorMessage: StateFlow<String?> = biometricError
    val isImporting: StateFlow<Boolean> = MutableStateFlow(false)

    private var importJob: Job? = null
    private var isImportCancelled = false

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refresh()
        }
    }

    fun canUseBiometrics(): Boolean = biometricHelper.canAuthenticate()

    // AUD-02: Expose authenticateWithBiometrics alias
    fun authenticateWithBiometrics(
        activity: FragmentActivity,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        unlockVault(activity, onSuccess, onError)
    }

    fun unlockVault(
        activity: FragmentActivity,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        biometricHelper.promptBiometric(
            activity = activity,
            title = "الخزنة المشفرة",
            subtitle = "يرجى تأكيد هويتك لفتح مقاطع الفيديو المحمية",
            onSuccess = {
                repository.setUnlocked(true)
                _isUnlocked.value = true
                _biometricError.value = null
                refresh()
                onSuccess()
            },
            onError = { err ->
                _biometricError.value = err
                onError(err)
            }
        )
    }

    fun lockVault() {
        repository.setUnlocked(false)
        _isUnlocked.value = false
    }

    /**
     * AUD-03: Imports video to encrypted vault.
     * Permanent safety rule: deleteOriginal is permanently disabled and ignored.
     * The original video file on phone storage is NEVER touched or deleted.
     */
    fun importVideoFromUri(
        sourceUri: Uri,
        displayName: String? = null,
        deleteOriginal: Boolean = false
    ) {
        importJob?.cancel()
        isImportCancelled = false

        importJob = viewModelScope.launch {
            _importState.value = VaultImportState.Encrypting(
                currentBytes = 0L,
                totalBytes = 0L,
                progress = 0f
            )

            val result = repository.importVideo(
                sourceUri = sourceUri,
                displayName = displayName,
                onProgress = { progress ->
                    _importState.update {
                        if (it is VaultImportState.Encrypting) {
                            it.copy(progress = progress)
                        } else it
                    }
                },
                isCancelled = { isImportCancelled }
            )

            if (result != null) {
                _importState.value = VaultImportState.Success
                refresh()
            } else {
                _importState.value = if (isImportCancelled) {
                    VaultImportState.Idle
                } else {
                    VaultImportState.Error("فشل تشفير واستيراد الفيديو إلى الخزنة")
                }
            }
        }
    }

    // Overload matching earlier signature for compatibility
    fun importVideoFromUri(
        uri: Uri,
        title: String,
        durationMs: Long,
        onComplete: (Boolean) -> Unit = {}
    ) {
        importVideoFromUri(sourceUri = uri, displayName = title, deleteOriginal = false)
        onComplete(true)
    }

    fun cancelImport() {
        isImportCancelled = true
        importJob?.cancel()
        _importState.value = VaultImportState.Idle
    }

    fun dismissImportState() {
        _importState.value = VaultImportState.Idle
    }

    fun renameVideo(videoId: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameVideo(videoId, newTitle)
        }
    }

    fun deleteVideo(videoId: Long) {
        viewModelScope.launch {
            repository.deleteVaultVideo(videoId)
        }
    }

    fun clearError() {
        _biometricError.value = null
    }

    companion object {
        fun provideFactory(
            repository: VaultRepository,
            context: Context
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val biometricHelper = VaultBiometricHelper(context)
                return VaultViewModel(repository, biometricHelper) as T
            }
        }
    }
}
