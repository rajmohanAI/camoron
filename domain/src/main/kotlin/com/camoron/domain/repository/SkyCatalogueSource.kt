package com.camoron.domain.repository

import com.camoron.domain.model.SkyCatalogue

/** Where Sky view's star catalogue comes from (bundled with the app). */
interface SkyCatalogueSource {
    suspend fun load(): SkyCatalogue
}
