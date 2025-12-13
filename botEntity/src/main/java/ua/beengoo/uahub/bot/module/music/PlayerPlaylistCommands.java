package ua.beengoo.uahub.bot.module.music;

import com.github.kaktushose.jda.commands.annotations.interactions.AutoComplete;
import com.github.kaktushose.jda.commands.annotations.interactions.Command;
import com.github.kaktushose.jda.commands.annotations.interactions.Interaction;
import com.github.kaktushose.jda.commands.annotations.interactions.Param;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.AutoCompleteEvent;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.CommandEvent;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import ua.beengoo.uahub.bot.ContextHolder;
import ua.beengoo.uahub.bot.Lang;
import ua.beengoo.uahub.bot.helper.paginator.ButtonPaginator;
import ua.beengoo.uahub.bot.layout.message.Embed;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylist;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylistTrack;
import ua.beengoo.uahub.bot.module.music.player.AudioTrackMeta;
import ua.beengoo.uahub.bot.module.music.player.PlayerController;
import ua.beengoo.uahub.bot.module.music.service.MusicService;
import ua.beengoo.uahub.bot.module.music.service.PlaylistService;
import ua.beengoo.uahub.bot.module.permissions.service.PermissionService;

@Interaction
/** Slash commands for managing personal playlists (create, add, remove, list, play). */
public class PlayerPlaylistCommands {
  private final PlaylistService playlistService;
  private final MusicService musicService;
  private final PermissionService permissionService;

  public PlayerPlaylistCommands() {
    this.playlistService = ContextHolder.getBean(PlaylistService.class);
    this.musicService = ContextHolder.getBean(MusicService.class);
    this.permissionService = ContextHolder.getBean(PermissionService.class);
  }

  private boolean has(CommandEvent event, String node, String fallback) {
    Member m = event.getMember();
    if (m != null && m.hasPermission(Permission.ADMINISTRATOR)) return true;
    boolean allowed =
        permissionService.has(
            event.getUser().getIdLong(),
            m != null ? m.getRoles().stream().map(Role::getIdLong).toList() : null,
            node);
    if (!allowed)
      event.reply(
          Embed.getError()
              .setTitle(Lang.get("perms.error.insufficient_rights.title"))
              .setDescription(Lang.get("perms.error.insufficient_rights.desc")));
    return allowed;
  }

  /** Create a playlist for the current user. */
  @Command(value = "playlist create", desc = "Створити плейлист")
  public void onCreate(CommandEvent event, @Param(name = "name", value = "Назва") String name) {
    if (!has(event, "music.pl.create", "music.perm.pl.create")) return;
    try {
      playlistService.create(event.getUser().getIdLong(), name);
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.created").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (IllegalArgumentException e) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.exists").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    }
  }

  /** Add a track by query/URL to a playlist. */
  @Command(value = "playlist add", desc = "Додати трек у плейлист")
  public void onAdd(
      CommandEvent event,
      @Param(name = "name", value = "Назва") String name,
      @Param(name = "url", value = "URL або пошук") String url) {
    if (!has(event, "music.pl.add", "music.perm.pl.add")) return;
    try {
      playlistService.addTrack(event.getUser().getIdLong(), name, url, null);
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.added").formatted(name, url))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (PlaylistService.DuplicateTrackException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.track_exists"))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (IllegalStateException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.limit"))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (IllegalArgumentException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getError()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.not_found").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    }
  }

  /** Remove a track at a 1-based position from a playlist. */
  @Command(value = "playlist remove", desc = "Видалити трек з плейлисту")
  public void onRemove(
      CommandEvent event,
      @Param(name = "name", value = "Назва") String name,
      @Param(name = "index", value = "Позиція (1..n)") String indexStr) {
    if (!has(event, "music.pl.remove", "music.perm.pl.remove")) return;
    int idx;
    try {
      idx = Integer.parseInt(indexStr);
    } catch (Exception e) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.invalid_index"))
                  .setDescription(indexStr)
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    idx = idx - 1;
    try {
      playlistService.removeTrack(event.getUser().getIdLong(), name, idx);
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.removed_index").formatted(idx + 1, name))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (IllegalArgumentException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getError()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.not_found").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    }
  }

  /** Delete a playlist. */
  @Command(value = "playlist delete", desc = "Видалити плейлист")
  public void onDelete(CommandEvent event, @Param(name = "name", value = "Назва") String name) {
    if (!has(event, "music.pl.delete", "music.perm.pl.delete")) return;
    try {
      playlistService.delete(event.getUser().getIdLong(), name);
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.deleted").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    } catch (IllegalArgumentException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getError()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.not_found").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
    }
  }

  /** List user playlists ordered by name. */
  @Command(value = "playlist list", desc = "Список плейлистів")
  public void onList(CommandEvent event) {
    if (!has(event, "music.pl.list", "music.perm.pl.list")) return;
    List<UserPlaylist> pls = playlistService.list(event.getUser().getIdLong());
    if (pls.isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.list.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    StringBuilder sb = new StringBuilder();
    for (UserPlaylist pl : pls) {
      sb.append("\n • ").append(pl.getName()).append(" (" + pl.getTracks().size() + ")");
    }
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.playlist.list.title"))
                .setDescription(sb.toString())
                .build())
        .setEphemeral(true)
        .queue();
  }

  /** View playlist contents with pagination. */
  @Command(value = "playlist view", desc = "Переглянути вміст плейлисту")
  public void onView(
      CommandEvent event, @Param(name = "name", value = "Назва плейлисту") String name) {
    if (!has(event, "music.pl.view", "music.perm.pl.view")) return;
    UserPlaylist pl;
    try {
      pl = playlistService.get(event.getUser().getIdLong(), name);
    } catch (IllegalArgumentException ex) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getError()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.not_found").formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }

    List<UserPlaylistTrack> tracks = new ArrayList<>(pl.getTracks());
    if (tracks.isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.view.title").formatted(pl.getName()))
                  .setDescription(Lang.get("music.playlist.view.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }

    List<MessageEmbed> pages = new ArrayList<>();
    List<List<UserPlaylistTrack>> chunks = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i += 10) {
      chunks.add(tracks.subList(i, Math.min(i + 10, tracks.size())));
    }
    int page = 0;
    int position = 0;
    for (var part : chunks) {
      page++;
      EmbedBuilder eb =
          Embed.getInfo().setTitle(Lang.get("music.playlist.view.title").formatted(pl.getName()));
      eb.setFooter(Lang.get("paginator.page").formatted(page, chunks.size()));
      StringBuilder sb = new StringBuilder();
      for (var t : part) {
        position++;
        String lineTitle =
            t.getTitle() != null && !t.getTitle().isBlank() ? t.getTitle() : t.getQuery();
        sb.append("\n **#%s** %s".formatted(position, lineTitle));
      }
      sb.append("\n\n" + Lang.get("music.playlist.view.total").formatted(tracks.size()));
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
                            new ButtonPaginator(pages, event.getUser(), true)
                                .paginate(sentMsg, event.getJDA())));
  }

  @AutoComplete(
      value = {"playlist view"},
      options = "name")
  public void onPlaylistPlayAuto(AutoCompleteEvent event) {
    String typed = event.jdaEvent().getFocusedOption().getValue();
    var playlists = playlistService.list(event.getUser().getIdLong());
    List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices =
        new ArrayList<>();
    for (var pl : playlists) {
      String nm = pl.getName();
      if (typed == null || typed.isBlank() || nm.toLowerCase().contains(typed.toLowerCase())) {
        choices.add(
            new net.dv8tion.jda.api.interactions.commands.Command.Choice(
                nm + " (" + pl.getTracks().size() + ")", nm));
        if (choices.size() >= 25) break;
      }
    }
    event.replyChoices(choices);
  }

  // Listener moved to MusicService; keep commands lean

  @Command(value = "playlist save", desc = "Зберегти поточну чергу як плейлист")
  public void onSaveFromQueue(
      CommandEvent event, @Param(name = "name", value = "Назва") String name) {
    if (!has(event, "music.pl.save", "music.perm.pl.save")) return;
    List<AudioTrackMeta> tracks = PlayerController.getInstance().getTracks();
    if (tracks.isEmpty()) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.queue.empty"))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    int total = Math.min(256, tracks.size());
    try {
      playlistService.create(event.getUser().getIdLong(), name);
    } catch (IllegalArgumentException e) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle("Плейлист")
                  .setDescription("Вже існує: %s".formatted(name))
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    int added = 0;
    for (int i = 0; i < total; i++) {
      AudioTrack t = tracks.get(i).getEntity();
      String q = buildQuery(t);
      String title = t.getInfo() != null ? t.getInfo().title : null;
      playlistService.addTrack(event.getUser().getIdLong(), name, q, title);
      added++;
    }
    if (tracks.size() > 256) {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getWarn()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.saved.limited").formatted(added, name))
                  .build())
          .setEphemeral(true)
          .queue();
    } else {
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.playlist.title"))
                  .setDescription(Lang.get("music.playlist.saved.ok").formatted(added, name))
                  .build())
          .setEphemeral(true)
          .queue();
    }
  }

  private String buildQuery(AudioTrack t) {
    String src = t.getSourceManager() != null ? t.getSourceManager().getSourceName() : "";
    String id = t.getIdentifier();
    String uri = t.getInfo() != null ? t.getInfo().uri : null;
    if (uri != null && !uri.isBlank()) return uri;
    if ("youtube".equalsIgnoreCase(src) && id != null)
      return "https://www.youtube.com/watch?v=" + id;
    return id != null ? id : (t.getInfo() != null ? t.getInfo().title : "");
  }
}
