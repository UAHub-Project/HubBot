package ua.beengoo.uahub.bot.module.music.player;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import ua.beengoo.uahub.bot.config.ConfigurationFile;

/**
 * Low-level player backed by LavaPlayer. Manages queue, repeat modes and emits events to {@link
 * PlayerInstanceListener}s.
 */
@Slf4j
public class PlayerInstance extends ListenerAdapter {

    @Getter private final AudioPlayerManager playerManager;
    @Getter private final List<AudioTrackMeta> tracks = new ArrayList<>();
    private static final int MAX_TRACKS = 1028;
    private final PlayerController playerController;
    private final ConfigurationFile config;
    private AudioPlayer audioPlayer;
    private int currentIndex;
    @Getter private boolean isPlaying = false;
    @Getter private PlayerMode playerMode = PlayerMode.NOTHING;

    @SuppressWarnings("deprecation")
    public PlayerInstance(PlayerController playerController, ConfigurationFile config) {
        this.playerController = playerController;
        this.config = config;

        playerManager = new DefaultAudioPlayerManager();

        playerManager
            .getConfiguration()
            .setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);

        // Create YouTube source manager with optional authentication
        YoutubeAudioSourceManager youtube = createYoutubeSourceManager();
        SoundCloudAudioSourceManager soundCloud = SoundCloudAudioSourceManager.createDefault();

        playerManager.registerSourceManager(youtube);
        playerManager.registerSourceManager(soundCloud);
        AudioSourceManagers.registerRemoteSources(
            playerManager,
            com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
    }

    /**
     * Creates YouTube source manager with OAuth authentication and remote cipher if configured.
     * Prioritizes clients that return Opus formats to avoid AAC decoder issues.
     */
    private YoutubeAudioSourceManager createYoutubeSourceManager() {
        // Create options for YouTube source
        dev.lavalink.youtube.YoutubeSourceOptions options = new dev.lavalink.youtube.YoutubeSourceOptions();

        // Configure remote cipher if enabled
        if (config.remoteCipher != null && config.remoteCipher.enabled) {
            log.info("Configuring remote cipher server: {}", config.remoteCipher.url);
            options.setRemoteCipherUrl(
                config.remoteCipher.url,
                config.remoteCipher.password
            );
        }

        YoutubeAudioSourceManager youtube;

        if (config.youtubeOAuth.enabled &&
            config.youtubeOAuth.refreshToken != null &&
            !config.youtubeOAuth.refreshToken.isEmpty()) {

            log.info("Initializing YouTube source with OAuth authentication");

            try {
                // Prioritize Web client (best Opus support) even with OAuth
                // TV clients are OAuth-capable but Web has better format compatibility
                youtube = new YoutubeAudioSourceManager(
                    options,
                    new Web(),              // Primary: Opus support, best reliability
                    new TvHtml5Embedded(),  // OAuth-capable fallback
                    new AndroidMusic()      // Final fallback with Opus
                );

                // Enable OAuth with refresh token
                youtube.useOauth2(config.youtubeOAuth.refreshToken, true);

                log.info("YouTube OAuth authentication configured successfully (Web + TV clients)");
                return youtube;

            } catch (Exception e) {
                log.error("Failed to configure YouTube OAuth, falling back to guest mode", e);
            }
        }

        // Fallback to guest mode - prioritize Opus-capable clients to avoid AAC decoder errors
        log.info("Using YouTube in guest mode with Opus-capable clients");
        youtube = new YoutubeAudioSourceManager(
            options,
            new Web(),          // Primary: Best Opus support
            new AndroidMusic(), // Secondary: Opus support, music-focused
            new Music()         // Tertiary: Search only
        );

        return youtube;
    }

    /** Lazily creates and returns the underlying {@link AudioPlayer}. */
    public AudioPlayer getPlayer() {
        if (audioPlayer == null) {
            audioPlayer = playerManager.createPlayer();
            setupPlayer();
        }
        return audioPlayer;
    }

    /** Wires listener callbacks to broadcast player start/end events. */
    private void setupPlayer() {
        audioPlayer.addListener(
            new AudioEventAdapter() {
                @Override
                public void onTrackEnd(
                    AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
                    playerController
                        .getListeners()
                        .forEach(l -> l.onTrackEnd(fetchMetaFromEntity(track), endReason, player));
                }

                @Override
                public void onTrackStart(AudioPlayer player, AudioTrack track) {
                    playerController
                        .getListeners()
                        .forEach(l -> l.onTrackPlaying(fetchMetaFromEntity(track), player));
                }
            });
    }

    /** Resolves queue metadata for a raw LavaPlayer track entity. */
    public AudioTrackMeta fetchMetaFromEntity(AudioTrack entity) {
        for (AudioTrackMeta me : tracks) {
            if (me.getEntity().getIdentifier().equals(entity.getIdentifier())) {
                return me;
            }
        }
        log.warn("Track metadata not found. size={}, entity={}.", tracks.size(), entity);
        return null;
    }

    /** Sets repeat mode and notifies listeners. */
    public void setPlayerMode(PlayerMode playerMode) {
        playerController
            .getListeners()
            .forEach(l -> l.onPlayerModeChanged(getPlayerMode(), playerMode, getPlayer()));
        this.playerMode = playerMode;
    }

    /** Loads a track or playlist from a query/URL and emits appropriate callbacks. */
    public void loadPlaylistSequentially(List<String> queries) {
        if (queries.isEmpty()) return;
        loadTrack(queries, 0);
    }

    private void loadTrack(List<String> queries, int index) {
        if (index >= queries.size()) return;

        String query = queries.get(index);
        playerManager.loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack audioTrack) {
                playerController.getListeners().forEach(l -> l.onTrackLoaded(audioTrack, getPlayer()));
                loadTrack(queries, index + 1);
            }

            @Override
            public void playlistLoaded(AudioPlaylist audioPlaylist) {
                playerController.getListeners().forEach(l -> l.onPlaylistLoaded(audioPlaylist, getPlayer()));
                loadTrack(queries, index + 1);
            }

            @Override
            public void noMatches() {
                playerController.getListeners().forEach(l -> l.onSearchFailed(getPlayer()));
                loadTrack(queries, index + 1);
            }

            @Override
            public void loadFailed(FriendlyException e) {
                playerController.getListeners().forEach(l -> l.onLoadFailed(e, getPlayer()));
                loadTrack(queries, index + 1);
            }
        });
    }

    /** Adds a single track to the end of the queue (bounded size). */
    public void addToQueue(AudioTrackMeta track) {
        // Prevent duplicates by identifier
        String id = track.getEntity() != null ? track.getEntity().getIdentifier() : null;
        if (id != null) {
            for (AudioTrackMeta t : tracks) {
                if (t.getEntity() != null && id.equals(t.getEntity().getIdentifier())) {
                    return; // already queued
                }
            }
        }

        if (tracks.size() >= MAX_TRACKS) {
            // Remove oldest
            tracks.remove(0);
        }
        tracks.add(track);
        playerController.getListeners().forEach(l -> l.onTrackQueueAdded(track, getPlayer()));
    }

    /** Adds all tracks of a playlist to the end of the queue (bounded size). */
    public void addPlaylistToQueue(AudioPlaylistMeta playlist) {
        // Append only tracks that are not already present by identifier
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AudioTrackMeta t : tracks) {
            if (t.getEntity() != null) seen.add(t.getEntity().getIdentifier());
        }
        java.util.List<AudioTrackMeta> toAdd = new java.util.ArrayList<>();
        for (AudioTrackMeta meta : playlist.getTracks()) {
            String id = meta.getEntity() != null ? meta.getEntity().getIdentifier() : null;
            if (id == null || !seen.contains(id)) {
                toAdd.add(meta);
                if (id != null) seen.add(id);
            }
        }
        for (AudioTrackMeta meta : toAdd) {
            if (tracks.size() >= MAX_TRACKS) {
                // Remove oldest
                tracks.remove(0);
            }
            tracks.add(meta);
        }
        if (!toAdd.isEmpty()) {
            playerController.getListeners().forEach(l -> l.onPlaylistQueueAdded(playlist, getPlayer()));
        }
    }

    /** Starts playing current index; ends gracefully if queue is empty or finished. */
    public void playQueue() {
        if (tracks.isEmpty()) {
            isPlaying = false;
            log.warn("Queue is empty!");
            return;
        }

        if (currentIndex >= tracks.size()) {
            isPlaying = false;
            log.info("End of queue reached.");
            return;
        }

        AudioTrack track = tracks.get(currentIndex).getEntity().makeClone();
        isPlaying = true;
        log.trace("Now playing: {} (by {})", track.getInfo().title, track.getInfo().author);
        getPlayer().playTrack(track);
    }

    /** Advances according to current repeat mode. */
    public void playNext() {
        playNext(false);
    }

    /** Advances to next track with optional override of repeat behavior. */
    public void playNext(boolean ignorePlayerMode) {
        PlayerMode localPlayerMode = ignorePlayerMode ? PlayerMode.NOTHING : playerMode;

        switch (localPlayerMode) {
            case REPEAT_ONE -> {
                log.info(
                    "Repeating track: {} (by {})",
                    tracks.get(currentIndex).getEntity().getInfo().title,
                    tracks.get(currentIndex).getEntity().getInfo().author);
                playQueue();
            }
            case REPEAT_QUEUE -> {
                currentIndex++;
                if (currentIndex >= tracks.size()) {
                    currentIndex = 0;
                    log.info("Restarting queue from beginning.");
                }
                playQueue();
            }
            case NOTHING -> {
                currentIndex++;
                if (currentIndex < tracks.size()) {
                    playQueue();
                } else {
                    isPlaying = false;
                    log.info("Queue finished.");
                    playerController.getListeners().forEach(l -> l.onQueueFinished(getPlayer()));
                }
            }
        }
    }

    /** True if another track exists after current index. */
    public boolean hasNext() {
        return currentIndex < (tracks.size() - 1);
    }

    /** Plays previous track depending on repeat mode. */
    public void playPrevious() {
        PlayerMode localPlayerMode = playerMode;
        if (tracks.isEmpty()) {
            isPlaying = false;
            log.warn("Queue is empty!");
            return;
        }
        switch (localPlayerMode) {
            case REPEAT_ONE, REPEAT_QUEUE -> {
                currentIndex--;
                if (currentIndex < 0) currentIndex = tracks.size() - 1;
                playQueue();
            }
            case NOTHING -> {
                if (currentIndex > 0) {
                    currentIndex--;
                    playQueue();
                } else {
                    // At the beginning: restart current
                    playQueue();
                }
            }
        }
    }

    /** Plays track at the given index if valid. */
    public void playAtIndex(int index) {
        if (tracks.isEmpty()) {
            isPlaying = false;
            log.warn("Queue is empty!");
            return;
        }
        if (index < 0 || index >= tracks.size()) {
            log.warn("Index out of range: {} (size {})", index, tracks.size());
            return;
        }
        currentIndex = index;
        playQueue();
    }

    /** Removes an item from queue and adjusts the current index if needed. */
    public void removeFromQueue(int index) {
        if (index < 0 || index >= tracks.size()) return;

        tracks.remove(index);
        if (currentIndex < index || currentIndex == index) {
            currentIndex = currentIndex - 1;
        }
    }

    /** Removes a specific meta from queue. */
    public void removeFromQueue(AudioTrackMeta track) {
        int index = tracks.indexOf(track);
        if (index != -1) {
            removeFromQueue(index);
        }
    }

    /** Skips the current track (removes it) and plays next. */
    public void skip() {
        playerController
            .getListeners()
            .forEach(
                l ->
                    l.onTrackSkipped(
                        fetchMetaFromEntity(getPlayer().getPlayingTrack().makeClone()), getPlayer()));
        try {
            removeFromQueue(fetchMetaFromEntity(audioPlayer.getPlayingTrack()));
            playNext(true);
        } catch (Throwable e) {
            log.debug("Exception while skipping track", e);
        }
    }

    /** Clears queue and stops the underlying player. */
    public void clear() {
        tracks.clear();
        isPlaying = false;
        currentIndex = 0;
        getPlayer().stopTrack();
        audioPlayer = null;
    }
}
