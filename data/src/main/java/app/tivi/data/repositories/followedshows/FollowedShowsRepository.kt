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

package app.tivi.data.repositories.followedshows

import app.tivi.data.entities.FollowedShowEntry
import app.tivi.data.entities.PendingAction
import app.tivi.data.entities.SortOption
import app.tivi.data.entities.Success
import app.tivi.data.instantInPast
import app.tivi.data.repositories.shows.ShowStore
import app.tivi.data.repositories.shows.ShowRepository
import app.tivi.extensions.doSingleLaunch
import app.tivi.extensions.parallelForEach
import app.tivi.inject.Trakt
import app.tivi.trakt.TraktAuthState
import app.tivi.util.Logger
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class FollowedShowsRepository @Inject constructor(
    private val followedShowsStore: FollowedShowsStore,
    private val followedShowsLastRequestStore: FollowedShowsLastRequestStore,
    private val showStore: ShowStore,
    @Trakt private val dataSource: FollowedShowsDataSource,
    private val showRepository: ShowRepository,
    private val traktAuthState: Provider<TraktAuthState>,
    private val logger: Logger
) {
    fun observeFollowedShows(sort: SortOption, filter: String? = null) = followedShowsStore.observeForPaging(sort, filter)

    fun observeIsShowFollowed(showId: Long) = followedShowsStore.observeIsShowFollowed(showId)

    suspend fun isShowFollowed(showId: Long) = followedShowsStore.isShowFollowed(showId)

    suspend fun getFollowedShows(): List<FollowedShowEntry> {
        return followedShowsStore.getEntries()
    }

    suspend fun needFollowedShowsSync(expiry: Instant = instantInPast(hours = 1)): Boolean {
        return followedShowsLastRequestStore.isRequestBefore(expiry)
    }

    suspend fun toggleFollowedShow(showId: Long) {
        if (isShowFollowed(showId)) {
            removeFollowedShow(showId)
        } else {
            addFollowedShow(showId)
        }
    }

    suspend fun addFollowedShow(showId: Long) {
        val entry = followedShowsStore.getEntryForShowId(showId)

        logger.d("addFollowedShow. Current entry: %s", entry)

        if (entry == null || entry.pendingAction == PendingAction.DELETE) {
            // If we don't have an entry, or it is marked for deletion, lets update it to be uploaded
            val newEntry = FollowedShowEntry(
                    id = entry?.id ?: 0,
                    showId = showId,
                    followedAt = entry?.followedAt ?: OffsetDateTime.now(),
                    pendingAction = PendingAction.UPLOAD
            )
            val newEntryId = followedShowsStore.save(newEntry)

            logger.v("addFollowedShow. Entry saved with ID: %s - %s", newEntryId, newEntry)

            // Now sync it up
            syncFollowedShows()
        }
    }

    suspend fun removeFollowedShow(showId: Long) {
        // Update the followed show to be deleted
        val entry = followedShowsStore.getEntryForShowId(showId)
        if (entry != null) {
            // Mark the show as pending deletion
            followedShowsStore.save(entry.copy(pendingAction = PendingAction.DELETE))
            // Now sync it up
            syncFollowedShows()
        }
    }

    suspend fun syncFollowedShows() {
        val listId = if (traktAuthState.get() == TraktAuthState.LOGGED_IN) getFollowedTraktListId() else null

        processPendingAdditions(listId)
        processPendingDelete(listId)

        if (listId != null) {
            pullDownTraktFollowedList(listId)
        }

        followedShowsLastRequestStore.updateLastRequest()
    }

    private suspend fun pullDownTraktFollowedList(listId: Int) = doSingleLaunch("pull_followed_list_$listId") {
        val response = dataSource.getListShows(listId)
        logger.d("pullDownTraktFollowedList. Response: %s", response)
        when (response) {
            is Success -> {
                response.data.map { (entry, show) ->
                    // Grab the show id if it exists, or save the show and use it's generated ID
                    val showId = showStore.getIdOrSavePlaceholder(show)
                    // Create a followed show entry with the show id
                    entry.copy(showId = showId)
                }.also { entries ->
                    // Save the show entriesWithShows
                    followedShowsStore.sync(entries)
                    // Now update all of the followed shows if needed
                    entries.parallelForEach { entry ->
                        if (showRepository.needsInitialUpdate(entry.showId)) {
                            showRepository.updateShow(entry.showId)
                        }
                        if (showRepository.needsImagesUpdate(entry.showId)) {
                            showRepository.updateShowImages(entry.showId)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processPendingAdditions(listId: Int?) {
        val pending = followedShowsStore.getEntriesWithAddAction()
        logger.d("processPendingAdditions. listId: %s, Entries: %s", listId, pending)

        if (pending.isEmpty()) {
            return
        }

        if (listId != null && traktAuthState.get() == TraktAuthState.LOGGED_IN) {
            val shows = pending.mapNotNull { showStore.getShow(it.showId) }
            logger.v("processPendingAdditions. Entries mapped: %s", shows)

            val response = dataSource.addShowIdsToList(listId, shows)
            logger.v("processPendingAdditions. Trakt response: %s", response)

            if (response is Success) {
                // Now update the database
                followedShowsStore.updateEntriesWithAction(pending.map { it.id }, PendingAction.NOTHING)
            }
        } else {
            // We're not logged in, so just update the database
            followedShowsStore.updateEntriesWithAction(pending.map { it.id }, PendingAction.NOTHING)
        }
    }

    private suspend fun processPendingDelete(listId: Int?) {
        val pending = followedShowsStore.getEntriesWithDeleteAction()
        logger.d("processPendingDelete. listId: %s, Entries: %s", listId, pending)

        if (pending.isEmpty()) {
            return
        }

        if (listId != null && traktAuthState.get() == TraktAuthState.LOGGED_IN) {
            val shows = pending.mapNotNull { showStore.getShow(it.showId) }
            logger.v("processPendingDelete. Entries mapped: %s", shows)

            val response = dataSource.removeShowIdsFromList(listId, shows)
            logger.v("processPendingDelete. Trakt response: %s", response)

            if (response is Success) {
                // Now update the database
                followedShowsStore.deleteEntriesInIds(pending.map { it.id })
            }
        } else {
            // We're not logged in, so just update the database
            followedShowsStore.deleteEntriesInIds(pending.map { it.id })
        }
    }

    private suspend fun getFollowedTraktListId(): Int? {
        return followedShowsStore.traktListId ?: dataSource.getFollowedListId()?.also { followedShowsStore.traktListId = it }
    }
}