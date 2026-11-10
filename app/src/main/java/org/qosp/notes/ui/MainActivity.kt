package org.qosp.notes.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.size
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.qosp.notes.BuildConfig
import org.qosp.notes.R
import org.qosp.notes.components.backup.BackupService
import org.qosp.notes.data.model.Attachment
import org.qosp.notes.data.model.Notebook
import org.qosp.notes.data.sync.core.BackendProvider
import org.qosp.notes.data.sync.fs.StorageConfig
import org.qosp.notes.data.sync.fs.toVaultFolderName
import org.qosp.notes.databinding.ActivityMainBinding
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.data.sync.fs.vault.VaultForegroundRescan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.qosp.notes.preferences.SortNavdrawerNotebooksMethod
import org.qosp.notes.ui.attachments.fromUri
import org.qosp.notes.ui.utils.closeAndThen
import org.qosp.notes.ui.utils.collect
import org.qosp.notes.ui.utils.hideKeyboard
import org.qosp.notes.ui.utils.navigateSafely
import org.qosp.notes.ui.whatsnew.WhatsNewViewModel
import org.qosp.notes.ui.whatsnew.showWhatsNewDialog
import org.qosp.notes.ui.widget.WidgetUpdateHelper

class MainActivity : BaseActivity() {

    lateinit var appBarConfiguration: AppBarConfiguration
    lateinit var navController: NavController

    private lateinit var binding: ActivityMainBinding
    private val activityModel: ActivityViewModel by viewModel()
    private val whatsNewModel: WhatsNewViewModel by viewModel()
    private val backendProvider by inject<BackendProvider>()
    private var vaultFolderPromptShown = false

    private val topLevelMenu get() = binding.navigationView.menu
    private val notebooksMenu get() = topLevelMenu.findItem(R.id.menu_notebooks).subMenu

    private val primaryDestinations = setOf(
        R.id.fragment_main,
        R.id.fragment_archive,
        R.id.fragment_deleted,
        R.id.fragment_notebook
    )
    private val secondaryDestinations = setOf(
        R.id.fragment_about,
        R.id.fragment_editor,
        R.id.fragment_manage_notebooks,
        R.id.fragment_search,
        R.id.fragment_settings,
        R.id.fragment_tags,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()

        // androidx.fragment:1.3.3 caused the FragmentContainerView to apply padding to itself when
        // the attribute fitsSystemWindows is enabled. We override it here and let the fragments decide their padding
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHostFragment) { view, insets ->
            insets
        }

        // Apply insets to the NavigationView to prevent it from overlapping with the status bar
        // This is to fix the insets after Android 15 enforcing edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(binding.navigationView) { view, insets ->
            // Reduce padding but keep it below the status bar
            view.setPadding(0, 0, 0, 0)
            insets
        }

        setupDrawerHeader()

        WidgetUpdateHelper.updateAllWidgets(this)
        // A theme change recreates this activity; re-handling a share/VIEW intent would import
        // the same note again.
        if (savedInstanceState == null && intent != null) handleIntent(intent)

        // The ViewModel flag, not savedInstanceState, decides whether the dialog was already
        // shown, so recreation while it is open does not bring it back.
        showWhatsNewIfNeeded()

        onBackPressedDispatcher.addCallback(this) {
            if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                binding.drawer.closeDrawer(GravityCompat.START)
            } else if (navController.previousBackStackEntry != null) {
                navController.popBackStack()
            } else {
                moveTaskToBack(true)
            }
        }
    }

    private val vaultForegroundRescan: VaultForegroundRescan by inject()

    private fun showWhatsNewIfNeeded() {
        // Firebase TestLab runs should not be interrupted by the "What's New" dialog
        if (BuildConfig.TESTLAB_BUILD) return

        lifecycleScope.launch {
            val item = whatsNewModel.whatsNewToShow().first() ?: return@launch
            if (!whatsNewModel.markDialogShown()) return@launch
            showWhatsNewDialog(item)
            // Marked on show, not on dismiss: rotation may destroy the dialog without a dismiss.
            whatsNewModel.markLatestAsSeen()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val location = preferenceRepository
                .getEncryptedString(PreferenceRepository.STORAGE_LOCATION)
                .first()
            if (location.isNotBlank()) {
                if (vaultForegroundRescan.shouldRunRescan()) {
                    // Keeps vault-relative paths in ActivityViewModel in step with the refreshed index.
                    activityModel.syncAsync().await()
                }
            } else {
                promptForVaultFolderIfNeeded()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun promptForVaultFolderIfNeeded() {
        if (vaultFolderPromptShown) return
        vaultFolderPromptShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vault_onboarding_title)
            .setMessage(R.string.vault_onboarding_message)
            .setPositiveButton(R.string.preferences_file_storage_select) { _, _ ->
                navController.navigateSafely(R.id.fragment_settings)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    /**
     * Copies shared media from a URI to the app's private storage.
     * @param uri The source URI of the media file to copy
     * @return The new URI in app's private storage, or null if copy failed
     */
    private suspend fun copySharedMedia(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        activityModel.copyMediaToPrivateStorage(uri, contentResolver.getType(uri).orEmpty())
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val title = intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""
                val content = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                var uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                // Try getting URI from ClipData if not in EXTRA_STREAM
                if (uri == null && intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    uri = intent.clipData!!.getItemAt(0).uri
                }

                lifecycleScope.launch {
                    val args = bundleOf(
                        "transitionName" to "",
                        "newNoteTitle" to title,
                        "newNoteContent" to content,
                    )

                    if (uri != null) {
                        val newUri = copySharedMedia(uri)
                        if (newUri != null) {
                            withContext(Dispatchers.IO) {
                                val attachment = Attachment.fromUri(this@MainActivity, newUri)
                                args.putParcelableArray("newNoteAttachments", arrayOf(attachment))
                            }
                        }
                    }
                    navController.handleDeepLink(getDeepLink(args))
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                var uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                // Try getting URIs from ClipData if not in EXTRA_STREAM
                if (uris == null && intent.clipData != null) {
                    uris = ArrayList()
                    for (i in 0 until intent.clipData!!.itemCount) {
                        intent.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }

                if (!uris.isNullOrEmpty()) {
                    lifecycleScope.launch {
                        val args = bundleOf(
                            "transitionName" to "",
                            "newNoteTitle" to (intent.getStringExtra(Intent.EXTRA_TITLE) ?: ""),
                            "newNoteContent" to (intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""),
                        )

                        withContext(Dispatchers.IO) {
                            val attachments = uris.mapNotNull { uri ->
                                copySharedMedia(uri)?.let { newUri ->
                                    Attachment.fromUri(this@MainActivity, newUri)
                                }
                            }.toTypedArray()

                            if (attachments.isNotEmpty()) {
                                args.putParcelableArray("newNoteAttachments", attachments)
                            }
                        }
                        navController.handleDeepLink(getDeepLink(args))
                    }
                }
            }

            "org.qosp.notes.NEW_NOTE" -> {
                // Request to create a new note from the widget
                val args = bundleOf("transitionName" to "", "newNoteTitle" to "", "newNoteContent" to "")
                navController.handleDeepLink(getDeepLink(args))
                return
            }

            else -> {
                // Request to open a note from the widget
                val noteId = intent.getLongExtra("noteId", -1L)
                if (noteId > 0) {
                    val args = bundleOf("noteId" to noteId, "transitionName" to "")
                    navController.handleDeepLink(getDeepLink(args))
                    return
                } else {
                    navController.handleDeepLink(intent)
                }
            }
        }
    }

    private fun getDeepLink(args: Bundle): Intent? = NavDeepLinkBuilder(this@MainActivity)
        .setGraph(R.navigation.nav_graph)
        .setDestination(R.id.fragment_editor)
        .setArguments(args)
        .createTaskStackBuilder()
        .first()

    private fun setupDrawerHeader() {
        val header = binding.navigationView.getHeaderView(0)
        val syncSettingsButton =
            header.findViewById<AppCompatImageButton>(R.id.button_sync_settings)
        val textViewUsername = header.findViewById<AppCompatTextView>(R.id.text_view_username)
        val textViewProvider = header.findViewById<AppCompatTextView>(R.id.text_view_provider)

        // Fixes bug that causes the header to have large padding when the keyboard is open
        ViewCompat.setOnApplyWindowInsetsListener(header) { view, insets ->
            header.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0, 0)
            WindowInsetsCompat.CONSUMED
        }

        syncSettingsButton.setOnClickListener {
            binding.drawer.closeAndThen {
                navController.navigateSafely(R.id.fragment_settings)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                backendProvider.syncProvider.collect { backend ->
                    val uri = StorageConfig.storageLocation(preferenceRepository)
                        .filterNotNull().firstOrNull()?.location
                    if (backend != null && uri != null && uri.toString().isNotEmpty()) {
                        textViewUsername.text = uri.toVaultFolderName(applicationContext)
                        textViewProvider.text = getString(R.string.preferences_cloud_service_files)
                    } else {
                        textViewUsername.text = getString(R.string.vault_onboarding_title)
                        textViewProvider.text = getString(R.string.vault_onboarding_hint)
                    }
                }
            }
        }
    }

    private fun selectCurrentDestinationMenuItem(
        destinationId: Int? = null,
        arguments: Bundle? = null
    ) {
        val destinationId =
            when (val id = destinationId ?: navController.currentDestination?.id ?: return) {
                // Assign destinations that do not have a drawer entry to an existing entry
                R.id.fragment_search -> R.id.fragment_main
                else -> id
            }

        val arguments = arguments ?: navController.currentBackStackEntry?.arguments
        val notebookId = arguments?.getLong("notebookId", -1L)?.takeIf { it >= 0L }

        binding.navigationView.post {
            ((notebooksMenu?.children ?: emptySequence()) + topLevelMenu.children)
                .forEach { item ->
                    item.isChecked = when (notebookId) {
                        null -> item.itemId == destinationId
                        else -> item.itemId == notebookId.toInt()
                    }
                }
        }
    }

    private fun setupDrawerMenuItems() {
        // Alternative of setupWithNavController(), NavigationUI.java
        // Sets up click listeners for all drawer menu items except from notebooks.
        // Those are handled in createNotebookMenuItems()
        (topLevelMenu.children + listOfNotNull(notebooksMenu?.findItem(R.id.fragment_manage_notebooks)))
            .forEach { item ->
                if (item.itemId !in primaryDestinations + secondaryDestinations) return@forEach

                item.setOnMenuItemClickListener {
                    binding.drawer.closeAndThen {
                        navController.navigateSafely(item.itemId)
                    }
                    false // Returning true would cause the menu item to become checked.
                    // We check the menu items only when the destination changes.
                }
            }

        notebooksMenu?.findItem(R.id.nav_default_notebook)?.setOnMenuItemClickListener {
            binding.drawer.closeAndThen {
                navController.navigateSafely(
                    R.id.fragment_notebook,
                    bundleOf(
                        "notebookId" to R.id.nav_default_notebook.toLong(),
                        "notebookName" to getString(R.string.default_notebook),
                    )
                )
            }
            false // Returning true would cause the menu item to become checked.
            // We check the menu items only when the destination changes.
        }
    }

    private fun setupNavigation() {
        fun createNotebookMenuItems(notebooks: List<Notebook>, sort: SortNavdrawerNotebooksMethod) {

            // Sorting the notebooks.
            val sortedNotebooks: List<Notebook> = when (sort) {
                SortNavdrawerNotebooksMethod.CREATION_ASC -> notebooks.sortedBy { it.id }
                SortNavdrawerNotebooksMethod.CREATION_DESC -> notebooks.sortedByDescending { it.id }
                SortNavdrawerNotebooksMethod.TITLE_ASC -> notebooks.sortedBy { it.name }
                SortNavdrawerNotebooksMethod.TITLE_DESC -> notebooks.sortedByDescending { it.name }
                else -> notebooks.sortedBy { it.name }
            }

            // Displaying the notebooks.
            sortedNotebooks.forEach { notebook ->
                val menuItem = notebooksMenu?.findItem(notebook.id.toInt())
                if (menuItem != null && notebook.name != menuItem.title) {
                    menuItem.title = notebook.name
                }
                if (menuItem == null) {
                    notebooksMenu?.add(
                        R.id.section_notebooks,
                        notebook.id.toInt(),
                        0,
                        notebook.name
                    )
                        ?.setIcon(R.drawable.ic_notebook)
                        ?.setCheckable(true)
                        ?.setOnMenuItemClickListener {
                            binding.drawer.closeAndThen {
                                navController.navigateSafely(
                                    R.id.fragment_notebook,
                                    bundleOf(
                                        "notebookId" to notebook.id,
                                        "notebookName" to notebook.name,
                                    )
                                )
                            }
                            false // Returning true would cause the menu item to become checked.
                            // We check the menu items only when the destination changes.
                        }
                }
            }

            selectCurrentDestinationMenuItem()
        }

        appBarConfiguration = AppBarConfiguration(
            primaryDestinations,
            binding.drawer
        )

        navController =
            (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController

        setupDrawerMenuItems()

        navController.addOnDestinationChangedListener { controller, destination, arguments ->
            currentFocus?.hideKeyboard()
            selectCurrentDestinationMenuItem(destination.id, arguments)

            setDrawerEnabled(destination.id != R.id.fragment_editor)
        }

        activityModel.notebooks
            .combine(preferenceRepository.getAll().map { it.sortNavdrawerNotebooksMethod }) { (showDefaultNotebook, notebooks), sort ->
                Triple(showDefaultNotebook, notebooks, sort)
            }
            .collect(this) { (showDefaultNotebook, notebooks, sort) ->
                val notebookIds = (notebooks.map { it.id.toInt() } + R.id.nav_default_notebook).toSet()

                // Remove deleted notebooks from the menu
                (primaryDestinations + secondaryDestinations + notebookIds).let { dests ->
                    notebooksMenu?.let { nbMenu ->
                        var index = 0
                        while (index < nbMenu.size) {
                            val item = nbMenu[index]
                            if (item.itemId !in dests) nbMenu.removeItem(item.itemId) else index++
                        }
                    }
                }

                createNotebookMenuItems(notebooks, sort)

                val defaultTitle = getString(R.string.default_notebook)
                notebooksMenu?.findItem(R.id.nav_default_notebook)?.apply {
                    isVisible = showDefaultNotebook
                    title =
                        defaultTitle + " (${getString(R.string.default_string)})".takeIf { notebooks.any { it.name == defaultTitle } }
                            .orEmpty()
                }
            }
    }

    private fun setDrawerEnabled(enabled: Boolean) {
        binding.drawer.setDrawerLockMode(
            if (enabled) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )
    }

    fun startBackup(backupUri: Uri) {
        BackupService.backupNotes(this, activityModel.notesToBackup, backupUri)
    }

    fun restoreNotes(backupUri: Uri) {
        BackupService.restoreNotes(this, backupUri)
    }
}
