package com.zionhuang.music.db

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.paging.PagingSource
import com.zionhuang.music.constants.ORDER_ARTIST
import com.zionhuang.music.constants.ORDER_CREATE_DATE
import com.zionhuang.music.constants.ORDER_NAME
import com.zionhuang.music.constants.SongSortType
import com.zionhuang.music.db.daos.ArtistDao
import com.zionhuang.music.db.daos.ChannelDao
import com.zionhuang.music.db.daos.SongDao
import com.zionhuang.music.db.entities.ArtistEntity
import com.zionhuang.music.db.entities.ChannelEntity
import com.zionhuang.music.db.entities.Song
import com.zionhuang.music.db.entities.SongEntity
import com.zionhuang.music.extensions.getAudioFile
import com.zionhuang.music.extensions.getChannelAvatarFile
import com.zionhuang.music.extensions.getChannelBannerFile
import com.zionhuang.music.ui.fragments.songs.ChannelSongsFragment
import com.zionhuang.music.utils.OkHttpDownloader
import com.zionhuang.music.utils.OkHttpDownloader.requestOf
import com.zionhuang.music.youtube.YouTubeExtractor
import com.zionhuang.music.youtube.models.YouTubeChannel
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SongRepository(private val context: Context) {
    private val musicDatabase = MusicDatabase.getInstance(context)
    private val songDao: SongDao = musicDatabase.songDao
    private val artistDao: ArtistDao = musicDatabase.artistDao
    private val channelDao: ChannelDao = musicDatabase.channelDao

    private val youTubeExtractor = YouTubeExtractor.getInstance(context)

    /**
     * All Songs [PagingSource] with order [ORDER_CREATE_DATE], [ORDER_NAME], and [ORDER_ARTIST]
     */
    fun getAllSongsPagingSource(@SongSortType order: Int): PagingSource<Int, Song> = songDao.getAllSongsAsPagingSource(order)

    /**
     * All Songs [MutableList] with order [ORDER_CREATE_DATE], [ORDER_NAME], and [ORDER_ARTIST]
     */
    fun getAllSongsMutableList(@SongSortType order: Int): MutableList<Song> = when (order) {
        ORDER_CREATE_DATE -> songDao.getAllSongsByCreateDateAsMutableList()
        ORDER_NAME -> songDao.getAllSongsByNameAsMutableList()
        ORDER_ARTIST -> songDao.getAllSongsByArtistAsMutableList()
        else -> throw IllegalArgumentException("Unexpected order type.")
    }

    /**
     * Artist Songs [PagingSource]
     */
    fun getArtistSongsAsPagingSource(artistId: Int): PagingSource<Int, Song> = songDao.getArtistSongsAsPagingSource(artistId)

    /**
     * Channel Songs [PagingSource]
     */
    fun getChannelSongsAsPagingSource(channelId: String): PagingSource<Int, Song> = songDao.getChannelSongsAsPagingSource(channelId)

    /**
     * Artists [List], [PagingSource]
     */
    val allArtists: List<ArtistEntity> get() = artistDao.getAllArtists()
    val allArtistsPagingSource: PagingSource<Int, ArtistEntity> get() = artistDao.getAllArtistsAsPagingSource()

    /**
     * Channels [PagingSource]
     */
    val allChannelsPagingSource: PagingSource<Int, ChannelEntity> get() = channelDao.getAllChannelsAsPagingSource()

    /**
     * [ChannelSongsFragment] methods
     */
    fun channelSongsCount(channelId: String) = songDao.channelSongsCount(channelId)
    fun channelSongsDuration(channelId: String) = songDao.channelSongsDuration(channelId)

    /**
     * Song Operations
     */
    suspend fun getSongEntityById(id: String): SongEntity? = withContext(IO) { songDao.getSongEntityById(id) }
    suspend fun getSongById(id: String): Song? = withContext(IO) { songDao.getSongById(id) }

    suspend fun insert(song: SongEntity) = withContext(IO) { songDao.insert(song) }
    suspend fun insert(song: Song) = insert(song.toSongEntity())

    private suspend fun updateSong(song: SongEntity) = withContext(IO) { songDao.update(song) }
    suspend fun updateSong(song: Song) = updateSong(song.toSongEntity())
    suspend fun updateSong(songId: String, applier: SongEntity.() -> Unit) = updateSong(getSongEntityById(songId)!!.apply { applier(this) })

    suspend fun toggleLike(songId: String) {
        updateSong(songId) { liked = !liked }
    }

    suspend fun hasSong(songId: String) = withContext(IO) { songDao.contains(songId) }

    suspend fun deleteSong(song: SongEntity) = withContext(IO) { songDao.delete(song) }
    suspend fun deleteSong(songId: String) = withContext(IO) {
        songDao.delete(songId)
        context.getAudioFile(songId).let { file ->
            if (file.exists()) file.delete()
        }
    }

    /**
     * Artist Operations
     */

    suspend fun getArtist(artistId: Int): ArtistEntity? = withContext(IO) { artistDao.getArtist(artistId) }
    suspend fun getArtist(name: String): Int? = withContext(IO) { artistDao.getArtistId(name) }
    private suspend fun getOrInsertArtist(name: String): Int = withContext(IO) {
        getArtist(name) ?: insertArtist(name)
    }

    @WorkerThread
    fun searchArtists(query: CharSequence): List<ArtistEntity> = artistDao.searchArtists(query.toString())

    suspend fun insertArtist(name: String): Int = withContext(IO) { artistDao.insert(ArtistEntity(name = name)).toInt() }

    /**
     * Channel Operations
     */
    fun getChannelFlowById(channelId: String): Flow<ChannelEntity> = channelDao.getChannelFlowById(channelId)
    fun getChannelById(channelId: String): ChannelEntity? = channelDao.getChannelById(channelId)
    private suspend fun getChannelByName(name: String): ChannelEntity? = withContext(IO) { channelDao.getChannelByName(name) }
    private suspend fun getOrInsertChannel(chanelId: String, name: String): ChannelEntity = withContext(IO) {
        getChannelByName(name) ?: ChannelEntity(chanelId, name).apply { insertChannel(this) }
    }

    private suspend fun insertChannel(channel: ChannelEntity) = withContext(IO) {
        channelDao.insert(channel)
        downloadChannel(channel.id)
    }

    suspend fun downloadChannel(channelId: String) = withContext(IO) {
        val res = youTubeExtractor.getChannel(channelId)
        if (res is YouTubeChannel.Success) {
            OkHttpDownloader.downloadFile(requestOf(res.avatarUrl!!), context.getChannelAvatarFile(channelId))
            OkHttpDownloader.downloadFile(requestOf(res.bannerUrl!!), context.getChannelBannerFile(channelId))
        }
    }

    suspend fun deleteChannel(channel: ChannelEntity) = withContext(IO) {
        channelDao.delete(channel)
        context.getChannelAvatarFile(channel.id).delete()
        context.getChannelBannerFile(channel.id).delete()
    }

    suspend fun deleteChannel(channelId: String) = getChannelById(channelId)?.let { deleteChannel(it) }

    suspend fun hasChannel(channelId: String): Boolean = withContext(IO) { channelDao.contains(channelId) }

    /**
     * Extensions
     */
    private suspend fun Song.toSongEntity() = SongEntity(
            id,
            title,
            getOrInsertArtist(artistName),
            getOrInsertChannel(channelId, channelName).id,
            duration,
            liked,
            downloadState,
            createDate,
            modifyDate
    )

    companion object {
        private const val TAG = "SongRepository"
    }
}