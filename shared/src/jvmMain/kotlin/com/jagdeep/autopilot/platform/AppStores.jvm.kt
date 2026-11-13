package com.jagdeep.autopilot.platform

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jagdeep.persistence.preferences.DataStorePreferencesStore
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.preferences.AppPreferences
import java.io.File


actual fun createAppPreferencesStore(): PreferencesStore<AppPreferences> = DataStorePreferencesStore(
    dataStore = PreferenceDataStoreFactory.create { desktopDataPaths.file("settings",APP_PREFERENCES_FILE) },
    read = ::readAppPreferences,
    write = ::writeAppPreferences,
)

actual fun createDocumentStore(name: String): PreferencesStore<String> = DataStorePreferencesStore(
    dataStore = PreferenceDataStoreFactory.create { desktopDataPaths.file(when(name) {"routines"->"routines";"run_history"->"history";else->"settings"},documentFileName(name)) },
    read = { it[DOCUMENT_KEY].orEmpty() },
    write = { value, preferences -> preferences[DOCUMENT_KEY] = value },
)
