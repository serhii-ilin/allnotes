package org.qosp.notes.ui.whatsnew

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.mamoe.yamlkt.Yaml
import org.qosp.notes.data.WhatsNew
import org.qosp.notes.data.WhatsNewItem
import org.qosp.notes.data.latestId
import org.qosp.notes.data.updateToShow
import org.qosp.notes.preferences.PreferenceRepository

class WhatsNewViewModel(
    application: Application,
    private val preferenceRepository: PreferenceRepository
) : ViewModel() {

    // The dialog's lifecycle is not tied to a single activity instance: a rotation while it is
    // open destroys the activity but not this ViewModel, so the flag survives recreation.
    private var dialogShown = false

    // Parsed on first use so the activity start does not pay for the YAML read when there is nothing to show.
    private val whatsNew: WhatsNew by lazy {
        val yamlText = application.assets.open(WHATS_NEW_ASSET).bufferedReader().use { it.readText() }
        Yaml.decodeFromString(WhatsNew.serializer(), yamlText)
    }

    fun whatsNewToShow(): Flow<WhatsNewItem?> =
        whatsNew.toShowFlow(preferenceRepository.getEncryptedString(LAST_SHOWN_WHATS_NEW_ID))

    /** Returns false when this activity instance already showed (or is showing) the dialog. */
    fun markDialogShown(): Boolean {
        if (dialogShown) return false
        dialogShown = true
        return true
    }

    /**
     * Marks the update as seen as soon as the dialog is shown, not on dismiss: a rotation while it
     * is open would otherwise never deliver the dismiss callback. viewModelScope outlives the
     * activity, so the preference write is not cancelled by recreation.
     */
    fun markLatestAsSeen() {
        viewModelScope.launch { setLatestWhatsNewId() }
    }

    private suspend fun setLatestWhatsNewId() {
        preferenceRepository.putEncryptedStrings(LAST_SHOWN_WHATS_NEW_ID to whatsNew.latestId.toString())
    }

    companion object {
        private const val LAST_SHOWN_WHATS_NEW_ID = "LAST_SHOWN_WHATS_NEW_ID"
        private const val WHATS_NEW_ASSET = "whatsnew.yaml"
    }
}

/** Id assumed for users who never saw any update. */
private const val FIRST_UPDATE_ID = 1

/** Maps the stored "last shown id" preference to the update that should be announced. */
internal fun WhatsNew.toShowFlow(lastShownIds: Flow<String>): Flow<WhatsNewItem?> =
    lastShownIds.map { updateToShow(it.toIntOrNull() ?: FIRST_UPDATE_ID) }
