package ua.beengoo.uahub.bot.module.music.service;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.springframework.stereotype.Service;
import ua.beengoo.uahub.bot.module.music.player.*;
import java.util.List;

/**
 * Spring-managed facade around the music player to enable control from both Discord commands and
 * other application layers (e.g., web endpoints).
 */
@Service
public class MusicService {

  private final RuntimeListener runtimeListener = new RuntimeListener();
  private final MusicSettingsService settingsService;

  public MusicService(MusicSettingsService settingsService) {
    this.settingsService = settingsService;
    PlayerController.getInstance().addListener(runtimeListener);
  }

  /** Ensure audio handlers are set and connect the guild to a channel. */
  public void connect(Guild guild, AudioChannel channel) {
    if (guild == null || channel == null) return;
    PlayerController.getInstance().prepareHandlers(guild.getAudioManager());
    guild.getAudioManager().openAudioConnection(channel);
  }

  /** Queue a single query (URL or search string) and start playing if idle. */
  public void playQuery(Guild guild, Member requester, String query) {
    if (guild == null || requester == null || requester.getVoiceState() == null) return;
    connect(guild, requester.getVoiceState().getChannel());
    boolean setOwner = false;
    if (PlayerAccess.getOwnerId() == null) {
      PlayerAccess.setOwnerId(requester.getIdLong());
      setOwner = true;
    }
    runtimeListener.setLastRequester(requester);
    PlayerController.getInstance().requestToQueue(List.of(query));
    if (setOwner) applySavedSettings(requester.getIdLong());
  }

  /** Add a playlist (already materialized to queries) to queue. */
  public void playPlaylist(Guild guild, Member requester, List<String> queries, boolean replace) {
      if (guild == null || requester == null || requester.getVoiceState() == null) return;
      connect(guild, requester.getVoiceState().getChannel());
      boolean setOwner = false;
      if (PlayerAccess.getOwnerId() == null) {
          PlayerAccess.setOwnerId(requester.getIdLong());
          setOwner = true;
      }
      runtimeListener.setLastRequester(requester);
      if (replace) {
          PlayerController.getInstance().clean();
          PlayerController.getInstance().requestToQueue(queries);
          PlayerController.getInstance().playNext();
      } else {
          PlayerController.getInstance().requestToQueue(queries);
      }
      if (setOwner) applySavedSettings(requester.getIdLong());
  }

  private void applySavedSettings(long discordId) {
    settingsService
        .getSavedRepeatMode(discordId)
        .ifPresent(mode -> PlayerController.getInstance().setPlayerMode(mode));
  }

  /** Basic transport controls exposed for other layers. */
  public void pause(boolean val) {
    PlayerController.getInstance().setPaused(val);
  }

  public void skip() {
    PlayerController.getInstance().skip();
  }

  public void next() {
    PlayerController.getInstance().next();
  }

  public void previous() {
    PlayerController.getInstance().previous();
  }

  public void stop(Guild guild) {
    PlayerController.getInstance().clean();
    if (guild != null) guild.getAudioManager().closeAudioConnection();
    PlayerAccess.clearOwner();
  }

  /**
   * Returns a future that completes when the next queue addition (track or playlist) happens for
   * the given requester
   */
  public java.util.concurrent.CompletableFuture<QueueAddInfo> awaitQueueAddFor(long requesterId) {
    java.util.concurrent.CompletableFuture<QueueAddInfo> fut =
        new java.util.concurrent.CompletableFuture<>();
    PlayerInstanceListener listener =
        new PlayerInstanceListener() {
          @Override
          public void onPlaylistLoaded(
              com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist, AudioPlayer player) {}

          @Override
          public void onSearchFailed(AudioPlayer player) {}

          @Override
          public void onLoadFailed(FriendlyException e, AudioPlayer player) {}

          @Override
          public void onTrackPlaying(AudioTrackMeta track, AudioPlayer player) {}

          @Override
          public void onTrackEnd(
              AudioTrackMeta track, AudioTrackEndReason endReason, AudioPlayer player) {}

          @Override
          public void onTrackSkipped(AudioTrackMeta track, AudioPlayer player) {}

          @Override
          public void onTrackQueueAdded(AudioTrackMeta track, AudioPlayer player) {
            if (track != null
                && track.getEntityOwner() != null
                && track.getEntityOwner().getIdLong() == requesterId) {
              fut.complete(new QueueAddInfo(false, track, null));
            }
          }

          @Override
          public void onPlaylistQueueAdded(AudioPlaylistMeta playlist, AudioPlayer player) {
            if (playlist != null
                && playlist.getEntityOwner() != null
                && playlist.getEntityOwner().getIdLong() == requesterId) {
              fut.complete(new QueueAddInfo(true, null, playlist));
            }
          }

          @Override
          public void onTrackLoaded(
              com.sedmelluq.discord.lavaplayer.track.AudioTrack track, AudioPlayer player) {}

          @Override
          public void onPlayerModeChanged(
              PlayerMode modeBefore, PlayerMode modeAfter, AudioPlayer player) {}

          @Override
          public void onQueueFinished(AudioPlayer player) {}
        };
    PlayerController.getInstance().addListener(listener);
    fut.whenComplete((r, e) -> PlayerController.getInstance().removeListener(listener));
    return fut;
  }

  /** Info about what was added to the queue for a requester. */
  public static record QueueAddInfo(
      boolean playlist, AudioTrackMeta track, AudioPlaylistMeta playlistMeta) {}

  private static class RuntimeListener implements PlayerInstanceListener {
    private Member lastRequester;

    public void setLastRequester(Member m) {
      this.lastRequester = m;
    }

    @Override
    public void onPlaylistLoaded(
        AudioPlaylist playlist,
        AudioPlayer player) {
      PlayerController.getInstance().addToQueue(new AudioPlaylistMeta(playlist, lastRequester));
      if (!PlayerController.getInstance().isPlaying()) PlayerController.getInstance().playQueue();
    }

    @Override
    public void onSearchFailed(AudioPlayer player) {}

    @Override
    public void onLoadFailed(
        FriendlyException e,
        AudioPlayer player) {
    }

    @Override
    public void onTrackPlaying(
        AudioTrackMeta track, AudioPlayer player) {}

    @Override
    public void onTrackEnd(
        AudioTrackMeta track,
        com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason endReason,
        com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
        if (endReason.equals(AudioTrackEndReason.LOAD_FAILED)){
            PlayerController.getInstance().removeFromQueue(track);
            return;
        }
        if (endReason.mayStartNext) PlayerController.getInstance().playNext();
    }

    @Override
    public void onTrackSkipped(
        AudioTrackMeta track, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

    @Override
    public void onTrackQueueAdded(
        AudioTrackMeta track, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

    @Override
    public void onPlaylistQueueAdded(
        AudioPlaylistMeta playlist, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

    @Override
    public void onTrackLoaded(
        com.sedmelluq.discord.lavaplayer.track.AudioTrack track,
        com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
      PlayerController.getInstance().addToQueue(new AudioTrackMeta(track, lastRequester));
      if (!PlayerController.getInstance().isPlaying()) PlayerController.getInstance().playQueue();
    }

    @Override
    public void onPlayerModeChanged(
        PlayerMode modeBefore,
        PlayerMode modeAfter,
        com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

    @Override
    public void onQueueFinished(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}
  }
}
