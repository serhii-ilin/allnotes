package org.qosp.notes.ui.utils.coil

import android.content.Context
import android.view.View
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.widget.TextView
import coil.Coil.imageLoader
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.target.Target
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.DrawableUtils
import io.noties.markwon.image.ImageSpanFactory
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.commonmark.node.Image
import org.qosp.notes.R
import org.qosp.notes.data.sync.fs.vault.VaultPaths
import org.qosp.notes.data.sync.fs.vault.VaultTreeAccess
import org.qosp.notes.data.sync.fs.vault.resolveMarkwonImage
import org.qosp.notes.data.sync.fs.vault.resolveVaultStoredPath
import org.qosp.notes.data.sync.nextcloud.NextcloudConfig
import org.qosp.notes.preferences.PreferenceRepository
import org.qosp.notes.ui.utils.vault.VaultMarkwonContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adds Nextcloud auth headers and vault-relative image resolution for Markwon.
 */
class CoilImagesPlugin internal constructor(
    coilStore: CoilStore,
    imageLoader: ImageLoader,
) : AbstractMarkwonPlugin() {
    interface CoilStore {
        fun load(drawable: AsyncDrawable): ImageRequest
        fun cancel(disposable: Disposable)
    }

    private val coilAsyncDrawableLoader: CoilAsyncDrawableLoader = CoilAsyncDrawableLoader(coilStore, imageLoader)

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(Image::class.java, ImageSpanFactory())
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.asyncDrawableLoader(coilAsyncDrawableLoader)
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        AsyncDrawableScheduler.unschedule(textView)
    }

    override fun afterSetText(textView: TextView) {
        AsyncDrawableScheduler.schedule(textView)
    }

    private class CoilAsyncDrawableLoader(
        private val coilStore: CoilStore,
        private val imageLoader: ImageLoader,
    ) : AsyncDrawableLoader() {
        private val cache: MutableMap<AsyncDrawable, Disposable?> = HashMap(2)

        override fun load(drawable: AsyncDrawable) {
            val loaded = AtomicBoolean(false)
            val target: Target = AsyncDrawableTarget(drawable, loaded)
            val request = coilStore.load(drawable).newBuilder()
                .target(target)
                .build()
            val disposable = imageLoader.enqueue(request)
            if (!loaded.get()) {
                loaded.set(true)
                cache[drawable] = disposable
            }
        }

        override fun cancel(drawable: AsyncDrawable) {
            val disposable = cache.remove(drawable)
            if (disposable != null) {
                coilStore.cancel(disposable)
            }
        }

        override fun placeholder(drawable: AsyncDrawable): Drawable? = null

        private inner class AsyncDrawableTarget(
            private val drawable: AsyncDrawable,
            private val loaded: AtomicBoolean,
        ) : Target {
            override fun onSuccess(result: Drawable) {
                if (cache.remove(drawable) != null || !loaded.get()) {
                    loaded.set(true)
                    if (drawable.isAttached) {
                        DrawableUtils.applyIntrinsicBoundsIfEmpty(result)
                        drawable.result = result
                    }
                }
            }

            override fun onStart(placeholder: Drawable?) {
                if (placeholder != null && drawable.isAttached) {
                    DrawableUtils.applyIntrinsicBoundsIfEmpty(placeholder)
                    drawable.result = placeholder
                }
            }

            override fun onError(error: Drawable?) {
                if (cache.remove(drawable) != null) {
                    if (error != null && drawable.isAttached) {
                        DrawableUtils.applyIntrinsicBoundsIfEmpty(error)
                        drawable.result = error
                    }
                }
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            preferenceRepository: PreferenceRepository,
            vaultTreeAccess: VaultTreeAccess,
        ): CoilImagesPlugin {
            return create(
                object : CoilStore {
                    override fun load(drawable: AsyncDrawable): ImageRequest {
                        val host = drawable.callback
                        val viewCtx = (host as? View)?.context ?: context
                        val textView = host as? TextView
                        val dest = drawable.destination
                        val data: Any? = when {
                            dest.isNullOrBlank() -> null
                            VaultPaths.isVaultRelativeAttachment(dest) -> {
                                resolveVaultStoredPath(vaultTreeAccess, dest)
                            }
                            dest.contains("://") -> dest
                            else -> {
                                val vaultCtx =
                                    textView?.getTag(R.id.tag_vault_markwon_context) as? VaultMarkwonContext
                                resolveMarkwonImage(vaultTreeAccess, vaultCtx, dest)
                            }
                        }
                        return ImageRequest.Builder(viewCtx)
                            .data(data)
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .apply {
                                runBlocking {
                                    NextcloudConfig.fromPreferences(preferenceRepository).firstOrNull()
                                        ?.authenticationHeaders
                                        ?.forEach { (key, value) -> addHeader(key, value) }
                                }
                            }
                            .build()
                    }

                    override fun cancel(disposable: Disposable) {
                        disposable.dispose()
                    }
                },
                imageLoader(context),
            )
        }

        fun create(coilStore: CoilStore, imageLoader: ImageLoader): CoilImagesPlugin {
            return CoilImagesPlugin(coilStore, imageLoader)
        }
    }
}
