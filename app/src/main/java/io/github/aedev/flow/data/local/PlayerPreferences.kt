    }

    /**
     * The engine's last answer that this viewer needs no onboarding, so a cold start can pick its
     * first screen without waiting for the recommendation engine to load.
     */
    val onboardingDone: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.ONBOARDING_DONE] ?: true }

    suspend fun setOnboardingDone(done: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.ONBOARDING_DONE] = done
        }
    }