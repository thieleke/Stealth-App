package com.cosmos.unreddit.ui.mediaviewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.model.GalleryMedia
import com.cosmos.unreddit.data.model.GalleryMedia.Type
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.repository.GfycatRepository
import com.cosmos.unreddit.data.repository.ImgurRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.RedgifsRepository
import com.cosmos.unreddit.data.repository.StreamableRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.LinkUtil.https
import com.cosmos.unreddit.util.PostUtil
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MediaViewerViewModel
@Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val imgurRepository: ImgurRepository,
    private val streamableRepository: StreamableRepository,
    private val gfycatRepository: GfycatRepository,
    private val redgifsRepository: RedgifsRepository,
    private val postListRepository: PostListRepository,
    private val postMapper: PostMapper2,
    private val preferencesRepository: PreferencesRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _media: MutableStateFlow<Resource<List<GalleryMedia>>> =
        MutableStateFlow(Resource.Loading())
    val media: StateFlow<Resource<List<GalleryMedia>>> = _media

    private val _selectedPage: MutableStateFlow<Int> = MutableStateFlow(0)
    val selectedPage: StateFlow<Int> = _selectedPage

    /**
     * Download state of the media, by adapter position. Media that has never been downloaded has
     * no entry. Held here rather than in the fragment so the download button state survives
     * configuration changes.
     */
    private val _downloadStates: MutableStateFlow<Map<Int, DownloadState>> =
        MutableStateFlow(emptyMap())
    val downloadStates: StateFlow<Map<Int, DownloadState>> = _downloadStates

    val isMultiMedia: StateFlow<Boolean> = _media
        .filter { it is Resource.Success }
        .map { (it as Resource.Success).data.size > 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isVideoMuted: Flow<Boolean>
        get() = preferencesRepository.getMuteVideo(false)

    val largePreview: Flow<Boolean>
        get() = preferencesRepository.getLargePreview(false)

    var hideControls: Boolean = false

    /**
     * Mark [page] as downloading, then wait for the download enqueued as [workId] to finish and
     * mark it as downloaded if it succeeded. Observed from [viewModelScope] so the result is not
     * lost when the fragment view is recreated while the download is still running.
     */
    fun awaitDownload(page: Int, workId: UUID) {
        val previousState = _downloadStates.value[page]
        setDownloadState(page, DownloadState.DOWNLOADING)

        viewModelScope.launch {
            val workInfo = WorkManager.getInstance(appContext)
                .getWorkInfoByIdLiveData(workId)
                .asFlow()
                .filterNotNull()
                .first { it.state.isFinished }

            if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                setDownloadState(page, DownloadState.DOWNLOADED)
            } else {
                // Failed or cancelled: fall back to whatever the state was before this attempt
                setDownloadState(page, previousState)
            }
        }
    }

    private fun setDownloadState(page: Int, state: DownloadState?) {
        _downloadStates.updateValue(
            when (state) {
                null -> _downloadStates.value - page
                else -> _downloadStates.value + (page to state)
            }
        )
    }

    enum class DownloadState {
        DOWNLOADING,
        DOWNLOADED
    }

    init {
        viewModelScope.launch { preferencesRepository.getMuteVideo(false).first() }
    }

    fun loadMedia(link: String, mediaType: MediaType, forceUpdate: Boolean = false) {
        if (_media.value !is Resource.Success || forceUpdate) {
            viewModelScope.launch {
                val httpsLink = withContext(defaultDispatcher) { link.https }
                retrieveMedia(httpsLink, mediaType)
            }
        }
    }

    private suspend fun retrieveMedia(link: String, mediaType: MediaType) {
        when (mediaType) {
            MediaType.IMGUR_IMAGE, MediaType.IMAGE -> {
                setMedia(GalleryMedia.singleton(Type.IMAGE, link))
            }
            MediaType.IMGUR_LINK -> {
                val id = LinkUtil.getImageIdFromImgurLink(link)
                setMedia(GalleryMedia.singleton(Type.IMAGE, LinkUtil.getUrlFromImgurId(id)))
            }
            MediaType.IMGUR_GIF -> {
                setMedia(GalleryMedia.singleton(Type.VIDEO, LinkUtil.getImgurVideo(link)))
            }
            MediaType.REDDIT_GIF, MediaType.IMGUR_VIDEO, MediaType.VIDEO -> {
                setMedia(GalleryMedia.singleton(Type.VIDEO, link))
            }
            MediaType.REDDIT_VIDEO -> {
                setMedia(
                    GalleryMedia.singleton(Type.VIDEO, link, LinkUtil.getRedditSoundTrack(link))
                )
            }
            MediaType.GFYCAT -> {
                val id = LinkUtil.getGfycatId(link)

                gfycatRepository.getGfycatGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map {
                        GalleryMedia.singleton(Type.VIDEO, it.gfyItem.contentUrls.mp4.url)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDGIFS -> {
                val id = LinkUtil.getGfycatId(link)

                redgifsRepository.getRedgifsGif(id)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .map {
                        GalleryMedia.singleton(Type.VIDEO, it.gif.urls.hd)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.STREAMABLE -> {
                val shortcode = LinkUtil.getStreamableShortcode(link)
                streamableRepository.getVideo(shortcode).onStart {
                    _media.value = Resource.Loading()
                }.catch {
                    catchError(it)
                }.map { video ->
                    GalleryMedia.singleton(Type.VIDEO, video.files.mp4.url)
                }.collect {
                    setMedia(it)
                }
            }
            MediaType.IMGUR_ALBUM, MediaType.IMGUR_GALLERY -> {
                val albumId = LinkUtil.getAlbumIdFromImgurLink(link)
                imgurRepository.getAlbum(albumId)
                    .map { album ->
                        album.data.images.map { image ->
                            GalleryMedia(
                                if (image.preferVideo) Type.VIDEO else Type.IMAGE,
                                LinkUtil.getUrlFromImgurImage(image),
                                description = image.description
                            )
                        }
                    }
                    .map {
                        // Some Imgur galleries are empty and actually point to a single image
                        it.ifEmpty {
                            GalleryMedia.singleton(Type.IMAGE, LinkUtil.getUrlFromImgurId(albumId))
                        }
                    }
                    .flowOn(defaultDispatcher)
                    .onStart {
                        _media.value = Resource.Loading()
                    }
                    .catch {
                        catchError(it)
                    }
                    .collect {
                        setMedia(it)
                    }
            }
            MediaType.REDDIT_GALLERY -> {
                val permalink = LinkUtil.getPermalinkFromMediaUrl(link)
                postListRepository.getPost(permalink, Sorting(Sort.BEST)).onStart {
                    _media.value = Resource.Loading()
                }.catch {
                    catchError(it)
                }.map { listings ->
                    postMapper.dataToEntity(PostUtil.getPostData(listings)).gallery
                }.collect {
                    setMedia(it)
                }
            }
            else -> {
                _media.value = Resource.Error()
            }
        }
    }

    private fun catchError(throwable: Throwable) {
        when (throwable) {
            is IOException -> _media.value = Resource.Error(message = throwable.message)
            is HttpException -> _media.value = Resource.Error(throwable.code(), throwable.message())
            else -> _media.value = Resource.Error()
        }
    }

    fun setMedia(media: List<GalleryMedia>) {
        _media.updateValue(Resource.Success(media))
    }

    fun setMuted(mutedVideo: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setMuteVideo(mutedVideo)
        }
    }

    fun setSelectedPage(position: Int) {
        _selectedPage.updateValue(position)
    }
}
