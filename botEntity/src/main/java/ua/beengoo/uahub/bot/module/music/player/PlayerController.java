package ua.beengoo.uahub.bot.module.music.player;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.managers.AudioManager;
import ua.beengoo.uahub.bot.HubBot;

/** Facade over {@link PlayerInstance} that exposes player controls to commands and UI. */
public class PlayerController {
  private List<PlayerInstanceListener> listeners = new ArrayList<>();

  private static PlayerController instance;
  private final PlayerInstance playerInstance;

  /** Returns singleton instance of the controller. */
  public static PlayerController getInstance() {
    if (instance == null) instance = new PlayerController();
    return instance;
  }

  private PlayerController() {
    playerInstance = new PlayerInstance(this, HubBot.getConfig());
  }

  /** Registers a listener for player events. */
  public void addListener(PlayerInstanceListener l) {
    listeners.add(l);
  }

  /** Unregisters a listener. */
  public void removeListener(PlayerInstanceListener l) {
    listeners.remove(l);
  }

  /** Currently playing track or {@code null}. */
  public AudioTrack getNowPlayingTrack() {
    return playerInstance.getPlayer().getPlayingTrack();
  }

  List<PlayerInstanceListener> getListeners() {
    // Return a defensive copy to avoid ConcurrentModificationException if listeners mutate inside
    // callbacks
    return new java.util.ArrayList<>(listeners);
  }

  /** Prepares audio handlers and self-deafens the bot. */
  public void prepareHandlers(AudioManager am) {
    am.setSelfDeafened(true);
    am.setSendingHandler(new PlayerSendHandler(playerInstance.getPlayer()));
  }

  /** Loads a track or playlist by query and queues it. */
  public void requestToQueue(List<String> query) {
    playerInstance.loadPlaylistSequentially(query);
  }

  /** Starts or continues playback from the current index. */
  public void playQueue() {
    playerInstance.playQueue();
  }

  /** Moves to the next track using current {@link PlayerMode}. */
  public void playNext() {
    playerInstance.playNext();
  }

  /** Pauses or resumes playback. */
  public void setPaused(boolean val) {
    playerInstance.getPlayer().setPaused(val);
  }

  /** Returns whether playback is currently paused. */
  public boolean isPaused() {
    return playerInstance.getPlayer().isPaused();
  }

  /** Plays the next track optionally ignoring repeat mode. */
  public void playNext(boolean ignorePlayerMode) {
    playerInstance.playNext(ignorePlayerMode);
  }

  /** True if any track is currently playing. */
  public boolean isPlaying() {
    return playerInstance.isPlaying();
  }

  /** Adds a single track meta to the queue. */
  public void addToQueue(AudioTrackMeta track) {
    playerInstance.addToQueue(track);
  }

  /** Resolves metadata wrapper for the provided entity. */
  public AudioTrackMeta fetchMetaFromEntity(AudioTrack entity) {
    return playerInstance.fetchMetaFromEntity(entity);
  }

  /** Adds all tracks from a playlist to the queue. */
  public void addToQueue(AudioPlaylistMeta playlist) {
    playerInstance.addPlaylistToQueue(playlist);
  }

  /** Clears queue and stops playback. */
  public void clean() {
    playerInstance.clear();
  }

  /** Skips current track and plays next. */
  public void skip() {
    playerInstance.skip();
  }

  /** Alias for {@link #playNext(boolean)} with override. */
  public void next() {
    playerInstance.playNext(true);
  }

  /** Plays previous track based on mode. */
  public void previous() {
    playerInstance.playPrevious();
  }

  /** Returns a snapshot of the current queue. */
  public List<AudioTrackMeta> getTracks() {
    return playerInstance.getTracks();
  }

  /** Sets repeat mode. */
  public void setPlayerMode(PlayerMode playerMode) {
    playerInstance.setPlayerMode(playerMode);
  }

  /** Removes a track at a given 0-based index. */
  public void removeFromQueue(int index) {
    playerInstance.removeFromQueue(index);
  }

  /** Removes a specific track from the queue. */
  public void removeFromQueue(AudioTrackMeta track) {
    playerInstance.removeFromQueue(track);
  }

  /** Gets current repeat mode. */
  public PlayerMode getPlayerMode() {
    return playerInstance.getPlayerMode();
  }

  /** Plays the track at the provided 0-based index. */
  public void playAt(int index) {
    playerInstance.playAtIndex(index);
  }

  /** True if there is a next track after the current index. */
  public boolean hasNext() {
    return playerInstance.hasNext();
  }
}
