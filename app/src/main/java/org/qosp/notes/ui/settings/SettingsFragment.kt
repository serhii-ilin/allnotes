package org.qosp.notes.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.toDisplayPath
import org.qosp.notes.databinding.FragmentSettingsBinding
import org.qosp.notes.preferences.AppPreferences
import org.qosp.notes.preferences.DateFormat
import org.qosp.notes.preferences.LayoutMode
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.preferences.TimeFormat
import org.qosp.notes.ui.MainActivity
import org.qosp.notes.ui.common.BaseFragment
import org.qosp.notes.ui.utils.RestoreNotesContract
import org.qosp.notes.ui.utils.StorageLocationContract
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.liftAppBarOnScroll
import org.qosp.notes.ui.utils.viewBinding
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SettingsFragment : BaseFragment(resId = R.layout.fragment_settings) {
    private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val model: SettingsViewModel by viewModel()

    private var appPreferences = AppPreferences()

    private var storageLocation: Uri? = null

    override val hasMenu = false
    override val toolbar: Toolbar
        get() = binding.layoutAppBar.toolbar
    override val toolbarTitle: String
        get() = getString(R.string.nav_settings)

    private val loadBackupLauncher = registerForActivityResult(RestoreNotesContract) { uri ->
        if (uri == null) return@registerForActivityResult
        (activity as MainActivity).restoreNotes(uri)
    }

    private val locationListener = registerForActivityResult(StorageLocationContract) { uri ->
        uri?.let {
            val previousLocation = storageLocation?.toString() ?: ""
            val newLocation = it.toString()
            Log.i(TAG, "Storing location: $it")
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context?.contentResolver?.takePersistableUriPermission(it, takeFlags)
            model.applyVaultFolderChange(newLocation, previousLocation)
        }
    }

    private var folderSwapDialog: AlertDialog? = null

    private fun showFolderSwapDialog(prompt: SettingsViewModel.FolderSwapPrompt) {
        val context = context ?: return
        if (folderSwapDialog?.isShowing == true) return
        folderSwapDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.vault_swap_title)
            .setMessage(getString(R.string.vault_swap_message, prompt.newLocation.toUri().toDisplayPath(context)))
            .setPositiveButton(R.string.vault_swap_move) { _, _ ->
                model.resolveFolderSwap(SettingsViewModel.FolderSwapChoice.MOVE_NOTES)
            }
            .setNeutralButton(R.string.vault_swap_open) { _, _ ->
                model.resolveFolderSwap(SettingsViewModel.FolderSwapChoice.OPEN_AS_IS)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                model.resolveFolderSwap(SettingsViewModel.FolderSwapChoice.CANCEL)
            }
            .setOnCancelListener { model.resolveFolderSwap(SettingsViewModel.FolderSwapChoice.CANCEL) }
            .show()
    }

    override fun onDestroyView() {
        // Dismissing (unlike cancelling) leaves the pick pending, so the recreated view shows the dialog again.
        folderSwapDialog?.dismiss()
        folderSwapDialog = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPreferenceObservers()
        setupVaultSettings()
        setupColorSchemeListener()
        setupThemeModeListener()
        setupDarkThemeModeListener()
        setupLayoutModeListener()
        setupSortMethodListener()
        setupSortTagsMethodListener()
        setupSortNavdrawerMethodListener()
        setupGroupNotesWithoutNotebookListener()
        setupMoveCheckedItemsListener()
        setupOpenMediaInListener()
        setupNoteDeletionTimeListener()
        setupBackupStrategyListener()
        setupShowDateListener()
        setupShowFontSizeListener()
        setupShowFabChangeModeListener()
        setupDefaultEditorModeListener()
        setupDateFormatListener()
        setupTimeFormatListener()

        binding.scrollView.liftAppBarOnScroll(
            binding.layoutAppBar.appBar,
            requireContext().resources.getDimension(R.dimen.app_bar_elevation)
        )

        binding.settingRestoreNotes.setOnClickListener { loadBackupLauncher.launch(null) }

        binding.settingBackupNotes.setOnClickListener {
            activityModel.notesToBackup = null
            exportNotesLauncher.launch(null)
        }
    }

    private fun setupPreferenceObservers() {
        model.appPreferences.collect(viewLifecycleOwner) {
            appPreferences = it

            with(appPreferences) {
                binding.settingLayoutMode.setIcon(
                    when (layoutMode) {
                        LayoutMode.GRID -> R.drawable.ic_grid
                        LayoutMode.LIST -> R.drawable.ic_list
                    }
                )
                binding.settingSortMethod.subText = getString(sortMethod.nameResource)
                binding.settingSortTagsMethod.subText = getString(sortTagsMethod.nameResource)
                binding.settingSortNavdrawerMethod.subText = getString(sortNavdrawerNotebooksMethod.nameResource)
                binding.settingBackupStrategy.subText = getString(backupStrategy.nameResource)
                binding.settingOpenMedia.subText = getString(openMediaIn.nameResource)
                binding.settingNoteDeletion.subText = getString(noteDeletionTime.nameResource)

                binding.settingGroupNotesWithoutNotebook.subText = getString(groupNotesWithoutNotebook.nameResource)
                binding.settingMoveCheckedItems.subText = getString(moveCheckedItems.nameResource)
                binding.settingShowDate.subText = getString(showDate.nameResource)
                binding.settingFontSize.subText = getString(editorFontSize.nameResource)
                binding.settingShowFab.subText = getString(showFabChangeMode.nameResource)
                binding.settingDefaultEditorMode.subText = getString(defaultEditorMode.nameResource)
                with(DateTimeFormatter.ofPattern(getString(dateFormat.patternResource))) {
                    binding.settingDateFormat.subText = format(LocalDate.now())
                }
                with(DateTimeFormatter.ofPattern(getString(timeFormat.patternResource))) {
                    binding.settingTimeFormat.subText = format(LocalTime.now())
                }

                binding.settingThemeMode.subText = getString(themeMode.nameResource)
                binding.settingDarkThemeMode.subText = getString(darkThemeMode.nameResource)
                binding.settingColorScheme.subText = getString(colorScheme.nameResource)
                binding.settingLayoutMode.subText = getString(layoutMode.nameResource)

            }
        }
    }

    private fun setupVaultSettings() {
        model.getEncryptedString(PreferenceRepository.STORAGE_LOCATION).collect(viewLifecycleOwner) { u ->
            val uri = u.toUri()
            storageLocation = uri
            val path = if (u.isNotBlank()) context?.let { uri.toDisplayPath(it) } else null
            binding.settingStorageLocation.subText =
                path?.takeIf { it.isNotBlank() } ?: getString(R.string.preferences_file_storage_choose_hint)
            model.refreshVaultRecoveryCount()
        }

        binding.settingStorageLocation.setOnClickListener {
            locationListener.launch(storageLocation)
        }

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

        model.folderSwapPrompts.collect(viewLifecycleOwner) { prompt -> showFolderSwapDialog(prompt) }
    }

    private fun setupColorSchemeListener() = binding.settingColorScheme.setOnClickListener {
        showPreferenceDialog(R.string.preferences_color_scheme, appPreferences.colorScheme) { selected ->
            // BaseActivity observes theme preferences, updates its theme cache and recreates itself.
            model.setPreference(selected)
        }
    }

    private fun setupThemeModeListener() = binding.settingThemeMode.setOnClickListener {
        showPreferenceDialog(R.string.preferences_theme_mode, appPreferences.themeMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupDarkThemeModeListener() = binding.settingDarkThemeMode.setOnClickListener {
        showPreferenceDialog(R.string.preferences_dark_theme_mode, appPreferences.darkThemeMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupLayoutModeListener() = binding.settingLayoutMode.setOnClickListener {
        showPreferenceDialog(R.string.preferences_layout_mode, appPreferences.layoutMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupSortMethodListener() = binding.settingSortMethod.setOnClickListener {
        showPreferenceDialog(R.string.preferences_sort_method, appPreferences.sortMethod) { selected ->
            model.setPreference(selected)
        }
    }

    /* Changes the sorting method of tags list. */
    private fun setupSortTagsMethodListener() = binding.settingSortTagsMethod.setOnClickListener {
        showPreferenceDialog(R.string.preferences_sort_tags_method, appPreferences.sortTagsMethod) { selected ->
            model.setPreference(selected)
        }
    }

    /* Changes the sorting method of notebooks list in the navigation drawer. */
    private fun setupSortNavdrawerMethodListener() = binding.settingSortNavdrawerMethod.setOnClickListener {
        showPreferenceDialog(R.string.preferences_sort_navdrawer_method, appPreferences.sortNavdrawerNotebooksMethod) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupBackupStrategyListener() = binding.settingBackupStrategy.setOnClickListener {
        showPreferenceDialog(R.string.preferences_backup_strategy, appPreferences.backupStrategy) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupGroupNotesWithoutNotebookListener() = binding.settingGroupNotesWithoutNotebook.setOnClickListener {
        showPreferenceDialog(
            R.string.preferences_group_notes_without_notebook,
            appPreferences.groupNotesWithoutNotebook
        ) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupMoveCheckedItemsListener() = binding.settingMoveCheckedItems.setOnClickListener {
        showPreferenceDialog(R.string.preferences_move_checked_items, appPreferences.moveCheckedItems) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupOpenMediaInListener() = binding.settingOpenMedia.setOnClickListener {
        showPreferenceDialog(R.string.preferences_open_media_in, appPreferences.openMediaIn) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupNoteDeletionTimeListener() = binding.settingNoteDeletion.setOnClickListener {
        showPreferenceDialog(R.string.preferences_note_deletion_time, appPreferences.noteDeletionTime) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupShowDateListener() = binding.settingShowDate.setOnClickListener {
        showPreferenceDialog(R.string.preferences_show_date, appPreferences.showDate) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupShowFontSizeListener() = binding.settingFontSize.setOnClickListener {
        showPreferenceDialog(R.string.preferences_font_size, appPreferences.editorFontSize) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupShowFabChangeModeListener() = binding.settingShowFab.setOnClickListener {
        showPreferenceDialog(R.string.preferences_show_fab_change_mode, appPreferences.showFabChangeMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupDefaultEditorModeListener() = binding.settingDefaultEditorMode.setOnClickListener {
        showPreferenceDialog(R.string.preferences_default_editor_mode, appPreferences.defaultEditorMode) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupTimeFormatListener() = binding.settingTimeFormat.setOnClickListener {
        val localTime = LocalTime.now()
        val items = TimeFormat.entries
            .map {
                DateTimeFormatter.ofPattern(getString(it.patternResource)).format(localTime)
            }
            .toTypedArray()

        showPreferenceDialog(R.string.preferences_time_format, appPreferences.timeFormat, items = items) { selected ->
            model.setPreference(selected)
        }
    }

    private fun setupDateFormatListener() = binding.settingDateFormat.setOnClickListener {
        val localDate = LocalDate.now()
        val items = DateFormat.entries
            .map {
                DateTimeFormatter.ofPattern(getString(it.patternResource)).format(localDate)
            }
            .toTypedArray()

        showPreferenceDialog(R.string.preferences_date_format, appPreferences.dateFormat, items = items) { selected ->
            model.setPreference(selected)
        }
    }
}
