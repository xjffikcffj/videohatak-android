package com.videohatak.player.feature.vault

sealed interface VaultImportState {
    data object Idle : VaultImportState
    data class Encrypting(
        val currentBytes: Long,
        val totalBytes: Long,
        val progress: Float
    ) : VaultImportState
    data object Success : VaultImportState
    data class Error(val message: String) : VaultImportState
}
