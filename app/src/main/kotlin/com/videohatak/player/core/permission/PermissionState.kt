package com.videohatak.player.core.permission

sealed interface VideoPermissionStatus {
    data object NotRequested : VideoPermissionStatus
    data object FullAccess : VideoPermissionStatus
    data object PartialAccess : VideoPermissionStatus
    data class Denied(val shouldShowRationale: Boolean) : VideoPermissionStatus
}

typealias PermissionState = VideoPermissionStatus
