package org.qosp.notes.ui.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.toDisplayPath
import org.qosp.notes.databinding.FragmentSyncSettingsBinding
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.common.BaseFragment
import org.qosp.notes.ui.settings.SettingsViewModel
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
        binding.layoutStorageSettings.visibility = View.VISIBLE
        binding.layoutNextcloudSettings.visibility = View.GONE
        binding.settingSyncProvider.visibility = View.GONE
        binding.settingSyncMode.visibility = View.GONE
        binding.settingNotesSyncableByDefault.visibility = View.GONE
        binding.settingTrustSelfSignedCertificate.visibility = View.GONE

        setupPreferenceObservers()
        setupLocalLocationListener()
        setupVaultMaintenance()
    }

    private fun setupVaultMaintenance() {
        binding.settingVaultRebuildIndex.setOnClickListener {
            model.rescanVault()
        }
        binding.settingVaultClearRecovery.setOnClickListener {
            if (model.vaultUiState.value.recoveryItems == 0) {
                model.clearVaultRecoveryState()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.preferences_vault_clear_recovery_dialog_title)
                .setMessage(R.string.preferences_vault_clear_recovery_dialog_message)
                .setPositiveButton(R.string.action_clear) { _, _ -> model.clearVaultRecoveryState() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        model.vaultUiState.collect(viewLifecycleOwner) { state ->
            with(binding.settingVaultRebuildIndex) {
                isEnabled = !state.rescanning
                alpha = if (state.rescanning) 0.5f else 1f
                subText = getString(
                    if (state.rescanning) R.string.preferences_vault_rescanning
                    else R.string.preferences_vault_rebuild_index_hint,
                )
            }
            binding.vaultRescanProgress.visibility = if (state.rescanning) View.VISIBLE else View.GONE

            with(binding.settingVaultClearRecovery) {
                isEnabled = !state.clearing
                alpha = if (state.clearing) 0.5f else 1f
                subText = when (val items = state.recoveryItems) {
                    null -> getString(R.string.preferences_vault_clear_recovery_hint)
                    0 -> getString(R.string.preferences_vault_no_recovery_items)
                    else -> resources.getQuantityString(R.plurals.vault_recovery_pending, items, items)
                }
            }
        }

        model.vaultMessages.collect(viewLifecycleOwner) { msg ->
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setupPreferenceObservers() {
        model.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).collect(viewLifecycleOwner) { u ->
            val uri = u.toUri()
            storageLocation = uri
            val path = if (u.isNotBlank()) context?.let { uri.toDisplayPath(it) } else null
            binding.settingStorageLocation.subText =
                path?.takeIf { it.isNotBlank() } ?: getString(R.string.preferences_file_storage_choose_hint)
            model.refreshVaultRecoveryCount()
        }
    }

    private fun setupLocalLocationListener() = binding.settingStorageLocation.setOnClickListener {
        locationListener.launch(storageLocation)
    }

    companion object {
        private const val TAG = "SyncSettings"
    }
}
