
@Test
fun test15_LegacyKeyMigrationWithoutDataLoss() = runBlocking {
    val scope = createTestScope()
    val (dataStore, _) = createRealDataStore(scope)

    val legacyKey = longPreferencesKey("playback_pos_888")
    dataStore.edit { prefs ->
        prefs[legacyKey] = 65_000L
    }

    val realRepo = PlaybackPreferencesRepositoryImpl(dataStore)

    // The final segment is deliberately non-numeric.
    // This prevents the repository from finding the legacy key again
    // when legacyVideoId is null, so the second read verifies the
    // migrated modern key rather than falling back to the old key.
    val mediaStoreUri =
        "content://media/external/video/media/legacy_migration_888"

    val migratedPos = realRepo.getSavedVideoPosition(
        mediaStoreUri,
        durationMs = 120_000L,
        legacyVideoId = 888L
    )

    assertEquals(
        "Must successfully read and migrate legacy position",
        65_000L,
        migratedPos
    )

    // Re-read using the same DataStore instance, without a legacy ID.
    // A successful result proves that the modern hashed key was persisted.
    val modernRead = realRepo.getSavedVideoPosition(
        mediaStoreUri,
        durationMs = 120_000L,
        legacyVideoId = null
    )

    assertEquals(
        "Modern key must persist migrated position without legacy fallback",
        65_000L,
        modernRead
    )
}
