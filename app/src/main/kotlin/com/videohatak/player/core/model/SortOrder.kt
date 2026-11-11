
package com.videohatak.player.core.model

enum class VideoSortField(val titleArabic: String) {
    DATE_ADDED("تاريخ الإضافة"),
    NAME("الاسم"),
    DURATION("المدة"),
    SIZE("الحجم")
}

enum class SortDirection(val titleArabic: String) {
    ASCENDING("تصاعدي"),
    DESCENDING("تنازلي")
}

data class VideoSortOrder(
    val field: VideoSortField = VideoSortField.DATE_ADDED,
    val direction: SortDirection = SortDirection.DESCENDING
)

enum class SortOrder(
    val field: VideoSortField,
    val direction: SortDirection
) {
    DATE_DESC(VideoSortField.DATE_ADDED, SortDirection.DESCENDING),
    DATE_ASC(VideoSortField.DATE_ADDED, SortDirection.ASCENDING),
    NAME_ASC(VideoSortField.NAME, SortDirection.ASCENDING),
    NAME_DESC(VideoSortField.NAME, SortDirection.DESCENDING),
    SIZE_DESC(VideoSortField.SIZE, SortDirection.DESCENDING),
    DURATION_DESC(VideoSortField.DURATION, SortDirection.DESCENDING);

    val asVideoSortOrder: VideoSortOrder
        get() = VideoSortOrder(this.field, this.direction)
}
