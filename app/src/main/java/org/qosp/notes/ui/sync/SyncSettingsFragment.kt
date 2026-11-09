package org.qosp.notes.ui.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.toFriendlyString
import org.qosp.notes.databinding.FragmentSyncSettingsBinding
import org.qosp.notes.preferences.AppPreferences
import org.qosp.notes.preferences.BackgroundSync
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.common.BaseFragment
import org.qosp.notes.ui.settings.SettingsViewModel
import org.qosp.notes.ui.settings.showPreferenceDialog
import org.qosp.notes.ui.utils.StorageLocationContract
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.liftAppBarOnScroll
import org.qosp.notes.ui.utils.viewBinding

class SyncSettingsFragment : BaseFragment(R.layout.fragment_sync_settings) {
    private val binding by viewBinding(FragmentSyncSettingsBinding::bind)
    private val model: SettingsViewModel by activityViewModel()

    override val hasMenu = false
    override val toolbar: Toolbar
        get() = binding.layoutAppBar.toolbar
    override val toolbarTitle: String
        get() = getString(R.string.preferences_header_syncing)

    private var appPreferences = AppPreferences()
    private var storageLocation: Uri? = null

    private val locationListener = registerForActivityResult(StorageLocationContract) { uri ->
        uri?.let {
            val previousLocation = storageLocation?.toString() ?: ""
            val newLocation = it.toString()
            model.applyVaultFolderChange(newLocation, previousLocation)
            Log.i(TAG, "Storing location: $it")
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context?.contentResolver?.takePersistableUriPermission(it, takeFlags)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scrollView.liftAppBarOnScroll(
            binding.layoutAppBar.appBar,
            requireContext().resources.getDimension(R.dimen.app_bar_elevation),
        )

        binding.layoutGenericSettings.visibility = View.VISIBLE
        binding.layoutNextcloudSettings.visibility = View.GONE
        binding.settingSyncProvider.visibility = View.GONE
        binding.settingSyncMode.visibility = View.GONE
        binding.settingNotesSyncableByDefault.visibility = View.GONE
        binding.settingTrustSelfSignedCertificate.visibility = View.GONE

        setupPreferenceObservers()
        setupBackgroundSyncListener()
        setupLocalLocationListener()
        binding.settingVaultRebuildIndex.setOnClickListener {
            model.rebuildVaultIndex()
        }
        binding.settingVaultClearRecovery.setOnClickListener {
            model.clearVaultRecoveryState()
        }
    }

    private fun setupPreferenceObservers() {
        model.appPreferences.collect(viewLifecycleOwner) { prefs ->
            appPreferences = prefs
            binding.layoutStorageSettings.visibility = View.VISIBLE
            binding.settingBackgroundSync.subText = getString(prefs.backgroundSync.nameResource)
        }

        model.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).collect(viewLifecycleOwner) { u ->
            val uri = u.toUri()
            storageLocation = uri
            val appName = if (u.isNotBlank()) context?.let { uri.toFriendlyString(it) } else null
            binding.settingStorageLocation.subText = appName ?: getString(R.string.preferences_file_storage_select)
        }
    }

    private fun setupLocalLocationListener() = binding.settingStorageLocation.setOnClickListener {
        locationListener.launch(storageLocation)
    }

    private fun setupBackgroundSyncListener() = binding.settingBackgroundSync.setOnClickListener {
        showPreferenceDialog(R.string.preferences_background_sync, appPreferences.backgroundSync) { selected ->
            model.setPreference(selected)
        }
    }

    companion object {
        private const val TAG = "SyncSettings"
    }
}
