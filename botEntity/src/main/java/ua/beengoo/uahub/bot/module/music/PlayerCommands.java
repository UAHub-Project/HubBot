package ua.beengoo.uahub.bot.module.music;

import com.github.kaktushose.jda.commands.annotations.interactions.AutoComplete;
import com.github.kaktushose.jda.commands.annotations.interactions.Command;
import com.github.kaktushose.jda.commands.annotations.interactions.Interaction;
import com.github.kaktushose.jda.commands.annotations.interactions.Param;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.AutoCompleteEvent;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.CommandEvent;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidMusic;
import dev.lavalink.youtube.clients.MWeb;
import dev.lavalink.youtube.clients.WebEmbedded;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import ua.beengoo.uahub.bot.ContextHolder;
import ua.beengoo.uahub.bot.Lang;
import ua.beengoo.uahub.bot.StringUtils;
import ua.beengoo.uahub.bot.helper.paginator.ButtonPaginator;
import ua.beengoo.uahub.bot.layout.message.Embed;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylist;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylistTrack;
import ua.beengoo.uahub.bot.module.music.player.*;
import ua.beengoo.uahub.bot.module.music.service.MusicService;
import ua.beengoo.uahub.bot.module.music.service.MusicSettingsService;
import ua.beengoo.uahub.bot.module.music.service.PlaylistService;
import ua.beengoo.uahub.bot.module.music.vote.VoteManager;
import ua.beengoo.uahub.bot.module.permissions.service.PermissionService;

@Interaction
/** Slash commands for the music player: play, pause, queue, navigation and control panel. */
public class PlayerCommands {

  private final PermissionService permissionService;
  private final MusicService musicService;
  private final PlaylistService playlistService;

  public PlayerCommands() {
    this.permissionService = ContextHolder.getBean(PermissionService.class);
    this.musicService = ContextHolder.getBean(MusicService.class);
    this.playlistService = ContextHolder.getBean(PlaylistService.class);
  }

  private boolean has(CommandEvent event, String node, String fallbackNodeKey) {
    if (event.getMember() != null && event.getMember().hasPermission(Permission.ADMINISTRATOR))
      return true;
    boolean allowed =
        permissionService.has(
            event.getUser().getIdLong(),
            event.getMember() != null
                ? event.getMember().getRoles().stream().map(Role::getIdLong).toList()
                : null,
            node);
    if (!allowed) {
      event.reply(
          Embed.getError()
              .setTitle(Lang.get("perms.error.insufficient_rights.title"))
              .setDescription(Lang.get("perms.error.insufficient_rights.desc")));
    }
    return allowed;
  }

  /** /player play — queue a track or playlist via URL or search query. */
  @Command(value = "player play", desc = "Відтворити трек або плейлист за URL/пошуком")
  public void onPlay(
      CommandEvent event,
      @Param(name = "url", value = "URL/пошук або назва плейлисту") String url) {
    if (!has(event, "music.play", "music.perm.play")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    if (url == null || url.isBlank()) {
      event.reply(Embed.getWarn().setTitle(Lang.get("music.play.provide_url")));
      return;
    }

    // Try to resolve as a user playlist first (exact name match)
    boolean handledAsPlaylist = false;
    try {
        UserPlaylist pl = playlistService.get(event.getUser().getIdLong(), url);
        List<String> queries = pl.getTracks().stream()
            .map(UserPlaylistTrack::getQuery)
            .toList();

        musicService.playPlaylist(event.getGuild(), m, queries);
        handledAsPlaylist = true;
        event
            .jdaEvent()
            .replyEmbeds(
                  Embed.getInfo()
                      .setTitle(Lang.get("music.playlist.title"))
                    .setDescription(
                          "Adding a user playlist: %s (%s)"
                              .formatted(pl.getName(), pl.getTracks().size())) // TODO: Lang
                    .build())
            .setEphemeral(true)
            .queue();
    } catch (IllegalArgumentException ignore) {}

    if (!handledAsPlaylist) {
      // Preload to detect duplicates for single track or YT/SC playlist
      Preview preview = previewLoad(url);
      if (preview != null) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (AudioTrackMeta t : PlayerController.getInstance().getTracks()) {
          if (t.getEntity() != null) existing.add(t.getEntity().getIdentifier());
        }
        long newCount =
            preview.tracks.stream()
                .map(AudioTrack::getIdentifier)
                .filter(id -> id != null && !existing.contains(id))
                .count();
        if (preview.isPlaylist && newCount == 0) {
          event
              .jdaEvent()
              .replyEmbeds(
                  Embed.getWarn()
                      .setTitle(Lang.get("music.player.title"))
                      .setDescription(Lang.get("music.playlist.all_duplicate"))
                      .build())
              .setEphemeral(true)
              .queue();
          return;
        }
        if (!preview.isPlaylist && newCount == 0) {
          event
              .jdaEvent()
              .replyEmbeds(
                  Embed.getWarn()
                      .setTitle(Lang.get("music.player.title"))
                      .setDescription(Lang.get("music.track.duplicate"))
                      .build())
              .setEphemeral(true)
              .queue();
          return;
        }
      }
      musicService.playQuery(event.getGuild(), m, url);
      // If we have preview and it's a single track, show title/author and artwork (if YouTube)
      if (preview != null) {
        AudioTrack first = preview.tracks().getFirst();
        String title = first.getInfo() != null ? first.getInfo().title : null;
        String author = first.getInfo() != null ? first.getInfo().author : null;
        var eb = Embed.getInfo().setTitle(Lang.get("music.player.title"));
        eb.setThumbnail(Utils.getArtwork(first));
        String displayAuthor = author != null && !author.isBlank() ? author : "?";
        String displayTitle = title != null && !title.isBlank() ? title : url;
        event
            .jdaEvent()
            .replyEmbeds(
                eb.setDescription(
                        Lang.get("music.play.added.track").formatted(displayAuthor, displayTitle))
                    .build())
            .setEphemeral(true)
            .queue();
      } else {
        try {
          var info =
              musicService
                  .awaitQueueAddFor(event.getUser().getIdLong())
                  .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                  .join();
          if (info != null && !info.playlist()) {
            var meta = info.track();
            var track = meta.getEntity();
            String title = track.getInfo() != null ? track.getInfo().title : null;
            String author = track.getInfo() != null ? track.getInfo().author : null;
            var eb = Embed.getInfo().setTitle(Lang.get("music.player.title"));
            try {
              String src =
                  track.getSourceManager() != null
                      ? track.getSourceManager().getSourceName()
                      : null;
              String id = track.getIdentifier();
              if (src != null && src.equalsIgnoreCase("youtube") && id != null && !id.isBlank()) {
                eb.setThumbnail("https://img.youtube.com/vi/" + id + "/hqdefault.jpg");
              }
            } catch (Throwable ignored) {
            }
            String displayAuthor = author != null && !author.isBlank() ? author : "?";
            String displayTitle = title != null && !title.isBlank() ? title : url;
            event
                .jdaEvent()
                .replyEmbeds(
                    eb.setDescription(
                            Lang.get("music.play.added.track")
                                .formatted(displayAuthor, displayTitle))
                        .build())
                .setEphemeral(true)
                .queue();
          } else {
            event
                .jdaEvent()
                .replyEmbeds(
                    Embed.getInfo()
                        .setTitle(Lang.get("music.player.title"))
                        .setDescription(Lang.get("music.play.added").formatted(url))
                        .build())
                .setEphemeral(true)
                .queue();
          }
        } catch (Throwable ex) {
          event
              .jdaEvent()
              .replyEmbeds(
                  Embed.getInfo()
                      .setTitle(Lang.get("music.player.title"))
                      .setDescription(Lang.get("music.play.added").formatted(url))
                      .build())
              .setEphemeral(true)
              .queue();
        }
      }
    }
  }

  @AutoComplete(value = {"player play"})
  public void onPlayAuto(AutoCompleteEvent event) {
    String typed = event.jdaEvent().getFocusedOption().getValue();
    java.util.List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices =
        new java.util.ArrayList<>();

    try {
      var playlists = playlistService.list(event.getUser().getIdLong());
      for (var pl : playlists) {
        String nm = pl.getName();
        if (typed == null || typed.isBlank() || nm.toLowerCase().contains(typed.toLowerCase())) {
          choices.add(
              new net.dv8tion.jda.api.interactions.commands.Command.Choice(
                  "Playlist: " + nm + " (" + pl.getTracks().size() + ")", nm));
          if (choices.size() >= 10) break;
        }
      }
    } catch (Throwable ignored) {
    }
    event.replyChoices(choices);
  }

  /** Toggle pause/resume. */
  @Command(value = "player pause", desc = "Пауза/продовжити відтворення")
  public void onPause(CommandEvent event) {
    if (!has(event, "music.pause", "music.perm.pause")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    PlayerController pc = PlayerController.getInstance();
    pc.setPaused(!pc.isPaused());
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(
                    pc.isPaused()
                        ? Lang.get("music.pause.paused")
                        : Lang.get("music.pause.resumed"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  /** Shows the current queue with pagination. */
  @Command(value = "player queue", desc = "Показати чергу відтворення")
  public void onQueue(CommandEvent event) {
    if (!has(event, "music.queue", "music.perm.queue")) return;
    List<AudioTrackMeta> tracks = PlayerController.getInstance().getTracks();
    if (tracks.isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.queue.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }

    List<MessageEmbed> pages = new ArrayList<>();
    List<List<AudioTrackMeta>> chunks = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i += 10) {
      chunks.add(tracks.subList(i, Math.min(i + 10, tracks.size())));
    }
    int page = 0;
    int position = 0;
    for (List<AudioTrackMeta> part : chunks) {
      page++;
      EmbedBuilder eb = Embed.getInfo().setTitle(Lang.get("music.player.title"));
      eb.setFooter(Lang.get("paginator.page").formatted(page, chunks.size()));
      StringBuilder sb = new StringBuilder();
      for (AudioTrackMeta meta : part) {
        position++;
        sb.append(
            "\n **#%s** %s — %s (%s) • %s"
                .formatted(
                    position,
                    meta.getEntity().getInfo().author,
                    meta.getEntity().getInfo().title,
                    fmtTime(meta.getEntity().getDuration()),
                    meta.getEntityOwner() != null
                        ? meta.getEntityOwner().getEffectiveName()
                        : "?"));
      }
      sb.append("\n\n" + Lang.get("music.queue.total").formatted(tracks.size()));
      eb.setDescription(sb.toString());
      pages.add(eb.build());
    }

    event
        .jdaEvent()
        .replyEmbeds(pages.getFirst())
        .setEphemeral(true)
        .queue(
            s ->
                s.retrieveOriginal()
                    .queue(
                        sentMsg ->
                            new ButtonPaginator(pages, event.getUser(), false)
                                .paginate(sentMsg, event.getJDA())));
  }

  /** Stops player and leaves voice. */
  @Command(value = "player bye", desc = "Зупинити плеєр і вийти")
  public void onBye(CommandEvent event) {
    if (!has(event, "music.bye", "music.perm.bye")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    Long ownerIdForStop = PlayerAccess.getOwnerId();
    boolean requireStop =
        ownerIdForStop != null
            && ContextHolder.getBean(MusicSettingsService.class)
                .getRequiredVoteActions(ownerIdForStop)
                .contains("stop");
    if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requireStop) {
      var ch = m.getVoiceState().getChannel();
      VoteManager.startVote(
          event.jdaEvent(),
          m,
          ch,
          "stop",
          Lang.get("music.vote.title"),
          Lang.get("music.vote.desc").formatted("stop"),
          () -> {
            PlayerController.getInstance().clean();
            if (event.getGuild() != null) event.getGuild().getAudioManager().closeAudioConnection();
            PlayerAccess.clearOwner();
          });
      return;
    }
    PlayerController.getInstance().clean();
    if (event.getGuild() != null) event.getGuild().getAudioManager().closeAudioConnection();
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.bye.stopped"))
                .build())
        .setEphemeral(true)
        .queue();
    PlayerAccess.clearOwner();
  }

  /** Skips current track (removes from queue). */
  @Command(value = "player skip", desc = "Пропустити поточний трек")
  public void onSkip(CommandEvent event) {
    if (!has(event, "music.skip", "music.perm.skip")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    if (!PlayerController.getInstance().isPlaying()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.nothing_playing"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    Long ownerIdForSkip = PlayerAccess.getOwnerId();
    boolean requireByOwner =
        ownerIdForSkip != null
            && ContextHolder.getBean(MusicSettingsService.class)
                .getRequiredVoteActions(ownerIdForSkip)
                .contains("skip");
    if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requireByOwner) {
      var ch = m.getVoiceState().getChannel();
      VoteManager.startVote(
          event.jdaEvent(),
          m,
          ch,
          "skip",
          Lang.get("music.vote.title"),
          Lang.get("music.vote.desc").formatted("skip"),
          () -> PlayerController.getInstance().skip());
      return;
    }
    PlayerController.getInstance().skip();
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.skip.ok"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  /** Plays the next track without removing current from queue. */
  @Command(value = "player next", desc = "Відтворити наступний трек (без видалення з черги)")
  public void onNext(CommandEvent event) {
    if (!has(event, "music.next", "music.perm.next")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    if (PlayerController.getInstance().getTracks().isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.queue.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    if (!PlayerController.getInstance().hasNext()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.next.no_next"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    Long ownerIdForNext = PlayerAccess.getOwnerId();
    boolean requireNext =
        ownerIdForNext != null
            && ContextHolder.getBean(MusicSettingsService.class)
                .getRequiredVoteActions(ownerIdForNext)
                .contains("next");
    if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requireNext) {
      var ch = m.getVoiceState().getChannel();
      VoteManager.startVote(
          event.jdaEvent(),
          m,
          ch,
          "next",
          Lang.get("music.vote.title"),
          Lang.get("music.vote.desc").formatted("next"),
          () -> PlayerController.getInstance().next());
      return;
    }
    PlayerController.getInstance().next();
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.next.ok"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  @Command(value = "player prev", desc = "Відтворити попередній трек")
  public void onPrev(CommandEvent event) {
    if (!has(event, "music.prev", "music.perm.prev")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    if (PlayerController.getInstance().getTracks().isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.queue.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    Long ownerIdForPrev = PlayerAccess.getOwnerId();
    boolean requirePrev =
        ownerIdForPrev != null
            && ContextHolder.getBean(MusicSettingsService.class)
                .getRequiredVoteActions(ownerIdForPrev)
                .contains("previous");
    if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requirePrev) {
      var ch = m.getVoiceState().getChannel();
      VoteManager.startVote(
          event.jdaEvent(),
          m,
          ch,
          "previous",
          Lang.get("music.vote.title"),
          Lang.get("music.vote.desc").formatted("previous"),
          () -> PlayerController.getInstance().previous());
      return;
    }
    PlayerController.getInstance().previous();
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.prev.ok"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  @Command(value = "player jump", desc = "Відтворити трек за позицією у черзі")
  public void onJump(
      CommandEvent event,
      @Param(name = "index", value = "Позиція у черзі (1..n)") String indexStr) {
    if (!has(event, "music.jump", "music.perm.jump")) return;
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    List<AudioTrackMeta> tracks = PlayerController.getInstance().getTracks();
    if (tracks.isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.queue.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    int idx;
    try {
      idx = Integer.parseInt(indexStr);
    } catch (Exception e) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.jump.invalid_index"))
                  .setDescription(indexStr)
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    idx = idx - 1; // 1-based to 0-based
    if (idx < 0 || idx >= tracks.size()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.jump.out_of_bounds"))
                  .setDescription("1..%s".formatted(tracks.size()))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }

    final int targetIdx = idx;
    Long ownerIdForJump = PlayerAccess.getOwnerId();
    boolean requireJump =
        ownerIdForJump != null
            && ContextHolder.getBean(MusicSettingsService.class)
                .getRequiredVoteActions(ownerIdForJump)
                .contains("jump");
    if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requireJump) {
      var ch = m.getVoiceState().getChannel();
      VoteManager.startVote(
          event.jdaEvent(),
          m,
          ch,
          "jump",
          Lang.get("music.vote.title"),
          Lang.get("music.vote.desc").formatted("jump"),
          () -> PlayerController.getInstance().playAt(targetIdx));
      return;
    }
    PlayerController.getInstance().playAt(targetIdx);
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.jump.ok").formatted(targetIdx + 1))
                .build())
        .setEphemeral(true)
        .queue();
  }

  @AutoComplete(value = {"player jump"})
  public void onJumpAuto(AutoCompleteEvent event) {
    var tracks = PlayerController.getInstance().getTracks();
    java.util.List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices =
        new java.util.ArrayList<>();
    String typed = event.jdaEvent().getFocusedOption().getValue();
    for (int i = 0; i < tracks.size() && choices.size() < 25; i++) {
      var t = tracks.get(i).getEntity();
      String rawLabel =
          "#%d (%s) %s — %s"
              .formatted(i + 1, fmtTime(t.getDuration()), t.getInfo().author, t.getInfo().title);
      String label = StringUtils.ellipsize(rawLabel, 100);
      String value = String.valueOf(i + 1);
      if (typed == null || typed.isBlank() || label.toLowerCase().contains(typed.toLowerCase())) {
        choices.add(new net.dv8tion.jda.api.interactions.commands.Command.Choice(label, value));
      }
    }
    event.replyChoices(choices);
  }

  @Command(value = "player np", desc = "Що зараз грає")
  public void onNowPlaying(CommandEvent event) {
    if (!has(event, "music.np", "music.perm.np")) return;
    AudioTrack now = PlayerController.getInstance().getNowPlayingTrack();
    if (now == null) {
      event.reply(Embed.getInfo().setTitle("Плеєр").setDescription("Нічого наразі не грає."));
      return;
    }
    AudioTrackMeta meta = PlayerController.getInstance().fetchMetaFromEntity(now);
    event.reply(
        Embed.getInfo()
            .setTitle("Плеєр")
            .setDescription(
                """
                        Зараз грає: **%s** - **%s**
                        Тривалість: `%s`
                        Додано: %s
                        """
                    .formatted(
                        now.getInfo().author,
                        now.getInfo().title,
                        fmtTime(now.getDuration()),
                        meta != null && meta.getEntityOwner() != null
                            ? meta.getEntityOwner().getEffectiveName()
                            : "?")));
  }

  @Command(value = "player ctrl", desc = "Панель керування плеєром")
  public void onCtrl(CommandEvent event) {
    // Allow open if user is session owner or has any control permission
    if (!PlayerAccess.canControl(event)) {
      event.reply(
          Embed.getError()
              .setTitle(Lang.get("perms.error.insufficient_rights.title"))
              .setDescription(Lang.get("perms.error.insufficient_rights.desc")));
      return;
    }

    // If an active panel exists and user is in the same voice channel as the bot, remove it first
    try {
      var guild = event.getGuild();
      var m = event.getMember();
      var botVc = guild != null ? guild.getAudioManager().getConnectedChannel() : null;
      var userVc = (m != null && m.getVoiceState() != null) ? m.getVoiceState().getChannel() : null;
      if (botVc != null && userVc != null && botVc.getIdLong() == userVc.getIdLong()) {
        PlayerControlPanel.removeActiveMessage();
      }
    } catch (Throwable ignored) {
    }

    event
        .jdaEvent()
        .reply("Please, wait")
        .setEphemeral(false)
        .queue(
            hook ->
                hook.retrieveOriginal()
                    .queue(
                        msg -> {
                          PlayerControlPanel.bindActive(msg, event.getJDA());
                          PlayerControlPanel.getInstance().refreshSilently();
                        }));
  }

  private static boolean isInVoice(Member m) {
    return m != null && m.getVoiceState() != null && m.getVoiceState().getChannel() != null;
  }

  /** Claim ownership if current owner left voice. */
  @Command(value = "player claim", desc = "Забрати контроль плеєра, якщо власник вийшов")
  public void onClaim(CommandEvent event) {
    Member m = event.getMember();
    if (!isInVoice(m)) {
      event
          .jdaEvent()
          .replyEmbeds(Embed.getError().setTitle(Lang.get("music.error.not_in_voice")).build())
          .setEphemeral(true)
          .queue();
      return;
    }
    Long ownerId = PlayerAccess.getOwnerId();
    boolean ownerGone = ownerId == null;
    if (!ownerGone) {
      var owner = event.getGuild() != null ? event.getGuild().getMemberById(ownerId) : null;
      ownerGone =
          (owner == null)
              || owner.getVoiceState() == null
              || owner.getVoiceState().getChannel() == null;
    }
    if (!ownerGone) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.player.title"))
                  .setDescription(Lang.get("music.claim.owner_present"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    PlayerAccess.setOwnerId(event.getUser().getIdLong());
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.player.title"))
                .setDescription(Lang.get("music.claim.ok"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  private static String fmtTime(long ms) {
    long totalSec = ms / 1000;
    long h = totalSec / 3600;
    long m = (totalSec % 3600) / 60;
    long s = totalSec % 60;
    if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
    return String.format("%d:%02d", m, s);
  }

  private static Preview previewLoad(String url) {
    try {
      AudioPlayerManager pm = new DefaultAudioPlayerManager();
      pm.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
      pm.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);
      dev.lavalink.youtube.YoutubeAudioSourceManager youtube =
          new dev.lavalink.youtube.YoutubeAudioSourceManager(
              true, new MWeb(), new AndroidMusic(), new WebEmbedded());
      SoundCloudAudioSourceManager soundCloud = SoundCloudAudioSourceManager.createDefault();
      pm.registerSourceManager(youtube);
      pm.registerSourceManager(soundCloud);
      // Register other remotes but exclude the default YouTube manager to avoid conflicts
      AudioSourceManagers.registerRemoteSources(pm, YoutubeAudioSourceManager.class);
      AtomicReference<List<AudioTrack>> tracks = new AtomicReference<>(java.util.List.of());
      AtomicBoolean isPl = new AtomicBoolean(false);
      CountDownLatch latch = new CountDownLatch(1);
      pm.loadItem(
          url,
          new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
              tracks.set(java.util.List.of(track));
              isPl.set(false);
              latch.countDown();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
              tracks.set(playlist.getTracks());
              isPl.set(true);
              latch.countDown();
            }

            @Override
            public void noMatches() {
              latch.countDown();
            }

            @Override
            public void loadFailed(FriendlyException e) {
              latch.countDown();
            }
          });
      latch.await(
          java.time.Duration.ofSeconds(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      var list = tracks.get();
      if (list == null || list.isEmpty()) return null; // no preview -> don't block
      return new Preview(isPl.get(), list);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private record Preview(boolean isPlaylist, java.util.List<AudioTrack> tracks) {}
}
