package com.camoron.data.processing

import android.content.Context
import com.camoron.domain.model.SkyCatalogue
import com.camoron.domain.repository.SkyCatalogueSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The star catalogue bundled in the app's assets, read once and kept. */
@Singleton
internal class AssetSkyCatalogueSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SkyCatalogueSource {

    private val lock = Mutex()
    private var cached: SkyCatalogue? = null

    override suspend fun load(): SkyCatalogue = lock.withLock {
        cached ?: withContext(Dispatchers.IO) {
            context.assets.open(ASSET).bufferedReader().useLines { SkyCatalogue.parse(it) }
        }.also { cached = it }
    }

    private companion object {
        const val ASSET = "sky_catalogue.txt"
    }
}
