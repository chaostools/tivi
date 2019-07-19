/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.data.repositories.shows

import app.tivi.data.entities.Success
import app.tivi.data.entities.TiviShow
import app.tivi.data.resultentities.ShowDetailed
import app.tivi.extensions.doSingleLaunch
import app.tivi.inject.Trakt
import org.threeten.bp.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShowRepositoryImpl @Inject constructor(
    private val showStore: ShowStore,
    private val showLastRequestStore: ShowLastRequestStore,
    private val showImagesLastRequestStore: ShowImagesLastRequestStore,
    private val tmdbShowImagesDataSource: TmdbShowImagesDataSource,
    @Trakt private val traktShowDataSource: ShowDataSource
) : ShowRepository {
    override fun observeShow(showId: Long) = showStore.observeShowDetailed(showId)

    override suspend fun getShow(showId: Long): TiviShow? {
        return showStore.getShow(showId)
    }

    /**
     * Updates the show with the given id from all network sources, saves the result to the database
     */
    override suspend fun updateShow(showId: Long) = doSingleLaunch("update_show_$showId") {
        val traktResult = traktShowDataSource.getShow(showStore.getShowOrEmpty(showId))
        if (traktResult is Success) {
            showStore.updateShowFromSources(showId, traktResult.get())

            // If the network requests were successful, update the last request timestamp
            showLastRequestStore.updateLastRequest(showId)
        }
    }

    override suspend fun updateShowImages(showId: Long) = doSingleLaunch("update_show_images_$showId") {
        val show = showStore.getShow(showId)
                ?: throw IllegalArgumentException("Show with ID $showId does not exist")
        when (val result = tmdbShowImagesDataSource.getShowImages(show)) {
            is Success -> {
                showStore.saveImages(showId, result.get().map { it.copy(showId = showId) })
                showImagesLastRequestStore.updateLastRequest(showId)
            }
        }
    }

    override suspend fun needsImagesUpdate(showId: Long, expiry: Instant): Boolean {
        return showImagesLastRequestStore.isRequestBefore(showId, expiry)
    }

    override suspend fun needsUpdate(showId: Long, expiry: Instant): Boolean {
        return showLastRequestStore.isRequestBefore(showId, expiry)
    }

    override suspend fun needsInitialUpdate(showId: Long): Boolean {
        return !showLastRequestStore.hasBeenRequested(showId)
    }

    override suspend fun searchShows(query: String): List<ShowDetailed> {
        return if (query.isNotBlank()) {
            showStore.searchShows(query)
        } else {
            emptyList()
        }
    }
}