package ua.beengoo.uahub.bot.module.music;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import lombok.Getter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import ua.beengoo.uahub.bot.ContextHolder;
import ua.beengoo.uahub.bot.Lang;
import ua.beengoo.uahub.bot.StringUtils;
import ua.beengoo.uahub.bot.helper.paginator.ButtonPaginator;
import ua.beengoo.uahub.bot.layout.message.Embed;
import ua.beengoo.uahub.bot.module.music.player.AudioPlaylistMeta;
import ua.beengoo.uahub.bot.module.music.player.AudioTrackMeta;
import ua.beengoo.uahub.bot.module.music.player.PlayerAccess;
import ua.beengoo.uahub.bot.module.music.player.PlayerController;
import ua.beengoo.uahub.bot.module.music.player.PlayerInstanceListener;
import ua.beengoo.uahub.bot.module.music.player.PlayerMode;
import ua.beengoo.uahub.bot.module.music.player.PlayerTextUtil;
import ua.beengoo.uahub.bot.module.music.service.MusicSettingsService;
import ua.beengoo.uahub.bot.module.music.vote.VoteManager;

/**
 * Interactive in-channel control panel driven by message buttons to control the player. Binds to a
 * single message and auto-expires after inactivity.
 */
public class PlayerControlPanel implements PlayerInstanceListener {

  private static PlayerControlPanel active;
  private Message message;
  private JDA jda;
  private static final long TTL_MILLIS = -1; // auto-close disabled
  private static final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();
  private ScheduledFuture<?> closeTask;
  @Getter
  private static PlayerControlPanel instance;

  private PlayerControlPanel() {
      instance = this;
  }

  /** Binds this control panel to a posted message and starts listening to button interactions. */
  public static void bindActive(Message msg, JDA jda) {
    if (active == null) {
      active = new PlayerControlPanel();
      PlayerController.getInstance().addListener(active);
      jda.addEventListener(active.listener);
    }
    active.message = msg;
    active.jda = jda;
    active.refreshSilently();
    active.touch();
  }

  /** Deletes currently active panel message (if any) and cleans up listeners. */
  public static boolean removeActiveMessage() {
    if (active == null) return false;
    try {
      if (active.closeTask != null) active.closeTask.cancel(false);
    } catch (Throwable ignored) {
    }
    try {
      if (active.message != null) active.message.delete().queue();
    } catch (Throwable ignored) {
    }
    try {
      if (active.jda != null) active.jda.removeEventListener(active.listener);
    } catch (Throwable ignored) {
    }
    try {
      PlayerController.getInstance().removeListener(active);
    } catch (Throwable ignored) {
    }
    active = null;
    return true;
  }

  /** Builds the full set of control buttons based on state. */
  public static List<Button> buildButtonsComponents(boolean paused, PlayerMode mode) {
    String pauseLabel = paused ? Lang.get("music.ctrl.resume") : Lang.get("music.ctrl.pause");
    String modeLabel =
        switch (mode) {
          case REPEAT_ONE -> Lang.get("music.ctrl.mode.single");
          case REPEAT_QUEUE -> Lang.get("music.ctrl.mode.queue");
          case NOTHING -> Lang.get("music.ctrl.mode.nothing");
        };
    return List.of(
        Button.primary("pl.prev", Lang.get("music.ctrl.prev")),
        Button.primary("pl.pause", pauseLabel),
        Button.primary("pl.next", Lang.get("music.ctrl.next")),
        Button.primary("pl.skip", Lang.get("music.ctrl.skip")),
        Button.primary("pl.queue", Lang.get("music.ctrl.queue")),
        Button.primary("pl.mode", modeLabel),
        Button.primary("pl.jump", Lang.get("music.ctrl.jump")),
        Button.danger("pl.bye", Lang.get("music.ctrl.bye")));
  }

    /** Wraps buttons into up to two rows. */
    public static List<ActionRow> buildRows(boolean paused, PlayerMode mode) {
        var buttons = buildButtonsComponents(paused, mode);
        if (buttons.size() <= 5) {
          return List.of(ActionRow.of(buttons));
        } else {
          return List.of(
              ActionRow.of(buttons.subList(0, 5)), ActionRow.of(buttons.subList(5, buttons.size())));
        }
    }
    private final ListenerAdapter listener =
      new ListenerAdapter() {
        @Override
        public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
          if (message == null || !event.getMessageId().equals(message.getId())) return;

          // Permission check: owner or has perms
          if (!PlayerAccess.canControl(event.getUser().getIdLong(), event.getMember())) {
            event
                .replyEmbeds(
                    Embed.getError()
                        .setTitle(Lang.get("perms.error.insufficient_rights.title"))
                        .setDescription(Lang.get("music.perm.ctrl"))
                        .build())
                .setEphemeral(true)
                .queue();
            return;
          }

          String id = event.getComponentId();
          boolean isOwner = PlayerAccess.isOwner(event.getUser().getIdLong());
          switch (id) {
            case "pl.prev" -> {
              if (!isOwner) {
                startVote(event, "previous", () -> PlayerController.getInstance().previous());
                return;
              }
              PlayerController.getInstance().previous();
            }
            case "pl.pause" -> {
              if (!isOwner) {
                startVote(
                    event,
                    "pause/resume",
                    () ->
                        PlayerController.getInstance()
                            .setPaused(!PlayerController.getInstance().isPaused()));
                return;
              }
              boolean newPaused = !PlayerController.getInstance().isPaused();
              PlayerController.getInstance().setPaused(newPaused);
            }
            case "pl.next" -> {
              if (!PlayerController.getInstance().hasNext()) {
                event
                    .replyEmbeds(
                        Embed.getWarn()
                            .setTitle(Lang.get("music.player.title"))
                            .setDescription(Lang.get("music.next.no_next"))
                            .build())
                    .setEphemeral(true)
                    .queue();
                return;
              }
              if (!isOwner) {
                startVote(event, "next", () -> PlayerController.getInstance().next());
                return;
              }
              PlayerController.getInstance().next();
            }
            case "pl.skip" -> {
              if (!isOwner) {
                startVote(event, "skip", () -> PlayerController.getInstance().skip());
                return;
              }
              PlayerController.getInstance().skip();
            }
            case "pl.queue" -> {
              // Show current queue to the button clicker, with paginator (ephemeral)
              var tracks = PlayerController.getInstance().getTracks();
              if (tracks.isEmpty()) {
                event
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
              for (var part : chunks) {
                page++;
                EmbedBuilder eb = Embed.getInfo().setTitle(Lang.get("music.player.title"));
                eb.setFooter(Lang.get("paginator.page").formatted(page, chunks.size()));
                StringBuilder sb = new StringBuilder();
                for (var meta : part) {
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
                  .replyEmbeds(pages.getFirst())
                  .setEphemeral(true)
                  .queue(
                      hook ->
                          hook.retrieveOriginal()
                              .queue(
                                  msg ->
                                      new ButtonPaginator(pages, event.getUser(), true)
                                          .paginate(msg, event.getJDA())));
              return; // do not refresh panel UI for queue view
            }
            case "pl.jump" -> {
              var tracks = PlayerController.getInstance().getTracks();
              if (tracks.isEmpty()) {
                event
                    .replyEmbeds(
                        Embed.getInfo()
                            .setTitle(Lang.get("music.player.title"))
                            .setDescription(Lang.get("music.queue.empty"))
                            .build())
                    .setEphemeral(true)
                    .queue();
                return;
              }
              var menuBuilder =
                  net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu.create(
                          "pl.jump.sel")
                      .setPlaceholder(Lang.get("music.jump.select"));
              int max = Math.min(25, tracks.size());
              for (int i = 0; i < max; i++) {
                var t = tracks.get(i).getEntity();
                String rawLabel =
                    "#%d (%s) %s — %s"
                        .formatted(
                            i + 1, fmtTime(t.getDuration()), t.getInfo().author, t.getInfo().title);

                String label = StringUtils.ellipsize(rawLabel, 100);

                menuBuilder.addOption(label, String.valueOf(i));
              }
              event.replyComponents(ActionRow.of(menuBuilder.build())).setEphemeral(true).queue();
              return; // no UI refresh
            }
            case "pl.mode" -> {
              PlayerMode mode = PlayerController.getInstance().getPlayerMode();
              PlayerMode next =
                  switch (mode) {
                    case REPEAT_ONE -> PlayerMode.REPEAT_QUEUE;
                    case REPEAT_QUEUE -> PlayerMode.NOTHING;
                    case NOTHING -> PlayerMode.REPEAT_ONE;
                  };
              if (!isOwner) {
                startVote(
                    event,
                    "mode:" + next,
                    () -> PlayerController.getInstance().setPlayerMode(next));
                return;
              }
              PlayerController.getInstance().setPlayerMode(next);
            }
              // removed confusing save button
            case "pl.bye" -> {
              if (!isOwner) {
                startVote(
                    event,
                    "stop",
                    () -> {
                      PlayerController.getInstance().clean();
                      PlayerAccess.clearOwner();
                      try {
                        if (event.getGuild() != null)
                          event.getGuild().getAudioManager().closeAudioConnection();
                      } catch (Throwable ignored) {
                      }
                    });
                return;
              }
              PlayerController.getInstance().clean();
              PlayerAccess.clearOwner();
              try {
                if (event.getGuild() != null)
                  event.getGuild().getAudioManager().closeAudioConnection();
              } catch (Throwable ignored) {
              }
            }
          }

          // Update panel UI
          boolean paused = PlayerController.getInstance().isPaused();
          PlayerMode mode = PlayerController.getInstance().getPlayerMode();
          MessageEmbed updated = buildStateEmbed();
          event.editMessageEmbeds(updated).setComponents(buildRows(paused, mode)).queue();
          touch();
        }

        @Override
        public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
          // Accept selections from the ephemeral jump menu (different message id)
          if (!"pl.jump.sel".equals(event.getComponentId())) return;
          if (!PlayerAccess.canControl(event.getUser().getIdLong(), event.getMember())) {
            event
                .replyEmbeds(
                    Embed.getError()
                        .setTitle(Lang.get("perms.error.insufficient_rights.title"))
                        .setDescription(Lang.get("music.perm.ctrl"))
                        .build())
                .setEphemeral(true)
                .queue();
            return;
          }
          var vals = event.getValues();
          if (vals == null || vals.isEmpty()) return;
          int idx;
          try {
            idx = Integer.parseInt(vals.getFirst());
          } catch (Exception ignored) {
            return;
          }
          Long ownerIdForJump = PlayerAccess.getOwnerId();
          boolean requireJump =
              ownerIdForJump != null
                  && ContextHolder.getBean(MusicSettingsService.class)
                      .getRequiredVoteActions(ownerIdForJump)
                      .contains("jump");
          if (!PlayerAccess.isOwner(event.getUser().getIdLong()) && requireJump) {
            var vs = event.getMember() != null ? event.getMember().getVoiceState() : null;
            var ch = vs != null ? vs.getChannel() : null;
            if (ch == null) {
              event
                  .replyEmbeds(
                      Embed.getError()
                          .setTitle(Lang.get("perms.error.insufficient_rights.title"))
                          .setDescription(Lang.get("music.perm.ctrl"))
                          .build())
                  .setEphemeral(true)
                  .queue();
              return;
            }
            VoteManager.startVote(
                event,
                event.getMember(),
                ch,
                "jump",
                Lang.get("music.vote.title"),
                Lang.get("music.vote.desc").formatted("jump"),
                () -> PlayerController.getInstance().playAt(idx));
            return;
          }
          PlayerController.getInstance().playAt(idx);
          event
              .replyEmbeds(
                  Embed.getInfo()
                      .setTitle(Lang.get("music.player.title"))
                      .setDescription(Lang.get("music.jump.ok").formatted(idx + 1))
                      .build())
              .setEphemeral(true)
              .queue();
        }
      };

  private void deny(ButtonInteractionEvent event) {
    event
        .replyEmbeds(
            Embed.getError()
                .setTitle(Lang.get("perms.error.insufficient_rights.title"))
                .setDescription(Lang.get("music.perm.ctrl"))
                .build())
        .setEphemeral(true)
        .queue();
  }

  private void startVote(ButtonInteractionEvent event, String actionKey, Runnable onSuccess) {
    var vs = event.getMember() != null ? event.getMember().getVoiceState() : null;
    var ch = (vs != null) ? vs.getChannel() : null;
    if (ch == null) {
      deny(event);
      return;
    }
    // If owner settings do not require vote for this action, execute directly
    Long ownerId = PlayerAccess.getOwnerId();
    if (ownerId != null) {
      var usvc = ContextHolder.getBean(MusicSettingsService.class);
      if (!usvc.getRequiredVoteActions(ownerId).contains(actionKey.split(":")[0])) {
        onSuccess.run();
        return;
      }
    }
    if (ch.getMembers().stream().filter(m -> !m.getUser().isBot()).collect(Collectors.toList()).size() >= 2) {
        String title = Lang.get("music.vote.title");
        String desc = Lang.get("music.vote.desc").formatted(actionKey);
        VoteManager.startVote(event, event.getMember(), ch, actionKey, title, desc, onSuccess);
    }
  }

  private boolean bypassVoting_removed(ButtonInteractionEvent event) {
    try {
      var svc = ContextHolder.getBean(MusicSettingsService.class);
      return svc.getBypassVoting(event.getUser().getIdLong()).orElse(false);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private MessageEmbed buildStateEmbed() {
    var pc = PlayerController.getInstance();
    var now = pc.getNowPlayingTrack();
    String np =
        (now == null)
            ? Lang.get("music.nothing_playing")
            : "**%s — %s** (%s)"
                .formatted(now.getInfo().author, now.getInfo().title, fmtTime(now.getDuration()));
    EmbedBuilder eb = Embed.getInfo().setTitle(Lang.get("music.ctrl.title"));
    eb.setDescription(
        """
                %s: %s
                %s: %s
                %s: %s
                """
            .formatted(
                Lang.get("music.ctrl.now"),
                np,
                Lang.get("music.ctrl.mode"),
                PlayerTextUtil.friendlyMode(pc.getPlayerMode()),
                Lang.get("music.ctrl.state"),
                (pc.isPaused()
                    ? Lang.get("music.ctrl.state.paused")
                    : Lang.get("music.ctrl.state.playing"))));
    eb.setThumbnail(Utils.getArtwork(now));
    return eb.build();
  }

  private static String fmtTime(long ms) {
    long totalSec = ms / 1000;
    long h = totalSec / 3600;
    long m = (totalSec % 3600) / 60;
    long s = totalSec % 60;
    if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
    return String.format("%d:%02d", m, s);
  }

  public void refreshSilently() {
    try {
      if (message == null) return;
      boolean paused = PlayerController.getInstance().isPaused();
      PlayerMode mode = PlayerController.getInstance().getPlayerMode();
      MessageEmbed updated = buildStateEmbed();
      message.editMessageEmbeds(updated).setComponents(buildRows(paused, mode)).setContent("").queue();
    } catch (Throwable ignored) {
    }
  }

  private void touch() {
    // auto-close disabled
  }

  private void closePanel() {
    try {
      if (message != null) {
        EmbedBuilder eb = Embed.getInfo().setTitle("Панель керування плеєром");
        eb.setDescription(Lang.get("music.ctrl.closed"));
        var rows = buildRows(false, PlayerMode.NOTHING);
        var disabledRows = new java.util.ArrayList<ActionRow>();
        for (ActionRow row : rows) {
          disabledRows.add(
              ActionRow.of(row.getButtons().stream().map(b -> b.withDisabled(true)).toList()));
        }
        message.editMessageEmbeds(eb.build()).setComponents(disabledRows).queue();
      }
    } catch (Throwable ignored) {
    }
    try {
      if (jda != null) jda.removeEventListener(listener);
    } catch (Throwable ignored) {
    }
    try {
      PlayerController.getInstance().removeListener(this);
    } catch (Throwable ignored) {
    }
    active = null;
  }

  // PlayerInstanceListener implementations to keep panel in sync
  @Override
  public void onTrackPlaying(
      AudioTrackMeta track, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    refreshSilently();
    touch();
  }

  @Override
  public void onTrackEnd(
      AudioTrackMeta track,
      com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason endReason,
      com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    refreshSilently();
    touch();
  }

  @Override
  public void onSearchFailed(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

  @Override
  public void onLoadFailed(
      com.sedmelluq.discord.lavaplayer.tools.FriendlyException e,
      com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

  @Override
  public void onPlaylistLoaded(
      com.sedmelluq.discord.lavaplayer.track.AudioPlaylist playlist,
      com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    refreshSilently();
    touch();
  }

  @Override
  public void onTrackQueueAdded(
      AudioTrackMeta track, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

  @Override
  public void onPlaylistQueueAdded(
      AudioPlaylistMeta playlist, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

  @Override
  public void onTrackLoaded(
      com.sedmelluq.discord.lavaplayer.track.AudioTrack track,
      com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {}

  @Override
  public void onPlayerModeChanged(
      PlayerMode modeBefore,
      PlayerMode modeAfter,
      com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    refreshSilently();
    touch();
  }

  @Override
  public void onTrackSkipped(
      AudioTrackMeta track, com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    refreshSilently();
    touch();
  }

  @Override
  public void onQueueFinished(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player) {
    try {
      if (message != null) {
        EmbedBuilder eb = Embed.getInfo().setTitle("Панель керування плеєром");
        eb.setDescription(Lang.get("music.queue.finished"));
        var rows = buildRows(false, PlayerMode.NOTHING);
        var disabledRows = new java.util.ArrayList<ActionRow>();
        for (ActionRow row : rows) {
          disabledRows.add(
              ActionRow.of(row.getButtons().stream().map(b -> b.withDisabled(true)).toList()));
        }
        message.editMessageEmbeds(eb.build()).setComponents(disabledRows).queue();
      }
    } catch (Throwable ignored) {
    }
//     try {
//       if (jda != null) jda.removeEventListener(listener);
//     } catch (Throwable ignored) {
//     }
//     try {
//       PlayerController.getInstance().removeListener(this);
//     } catch (Throwable ignored) {
//     }
//     active = null;
  }
}
