package ua.beengoo.uahub.bot.module.music;

import com.github.kaktushose.jda.commands.annotations.interactions.Choices;
import com.github.kaktushose.jda.commands.annotations.interactions.Command;
import com.github.kaktushose.jda.commands.annotations.interactions.Interaction;
import com.github.kaktushose.jda.commands.annotations.interactions.Param;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.CommandEvent;
import ua.beengoo.uahub.bot.ContextHolder;
import ua.beengoo.uahub.bot.Lang;
import ua.beengoo.uahub.bot.layout.message.Embed;
import ua.beengoo.uahub.bot.module.music.player.PlayerController;
import ua.beengoo.uahub.bot.module.music.player.PlayerMode;
import ua.beengoo.uahub.bot.module.music.player.PlayerTextUtil;
import ua.beengoo.uahub.bot.module.music.service.MusicSettingsService;
import java.util.Optional;

@Interaction
public class PlayerSettingsCommands {

  private final MusicSettingsService settingsService;

  public PlayerSettingsCommands() {
    this.settingsService = ContextHolder.getBean(MusicSettingsService.class);
  }

  @Command(value = "settings player repeat", desc = "Встановити і зберегти режим повторення")
  public void onSettingsRepeat(
      CommandEvent event,
      @Choices({"single", "queue", "nothing"}) @Param(name = "mode", optional = true, value = "Режим") String mode) {
    long uid = event.getUser().getIdLong();
    if (mode == null) {
        Optional<PlayerMode> userMode = settingsService.getSavedRepeatMode(uid);
        if (userMode.isPresent()) {
            event
                .jdaEvent()
                .replyEmbeds(
                    Embed.getInfo()
                        .setTitle(Lang.get("music.mode.title"))
                        .setDescription(
                            "Your current repeat mode is: %s".formatted(userMode.get())
                        )
                        .build())
                .setEphemeral(true)
                .queue();
        } else {
            event
                .jdaEvent()
                .replyEmbeds(
                    Embed.getWarn()
                        .setTitle(Lang.get("music.mode.title"))
                        .setDescription(
                            "Hmm, looks like you not configuring your player settings."
                        )
                        .build())
                .setEphemeral(true)
                .queue();
        }
        return;
    }
    var setTo =
        switch (mode) {
          case "single" -> PlayerMode.REPEAT_ONE;
          case "queue" -> PlayerMode.REPEAT_QUEUE;
          default -> PlayerMode.NOTHING;
        };
    PlayerController.getInstance().setPlayerMode(setTo);
    settingsService.saveRepeatMode(uid, setTo);
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.mode.title"))
                .setDescription(
                    Lang.get("music.mode.set").formatted(PlayerTextUtil.friendlyMode(setTo)))
                .build())
        .setEphemeral(true)
        .queue();
  }

  @Command(value = "settings player vote", desc = "Переглянути/налаштувати голосування для дій")
  public void onSettingsVote(
      CommandEvent event,
      @Choices({"pause", "next", "prev", "skip", "mode", "stop", "jump"})
          @Param(name = "key", optional = true)
          String key,
      @Choices({"true", "false"}) @Param(name = "value", optional = true) String value) {
    long uid = event.getUser().getIdLong();

    if (key == null || value == null) {
      var set = settingsService.getRequiredVoteActions(uid);
      String summary =
          "Require vote: %s".formatted(set.isEmpty() ? "(none)" : String.join(", ", set));
      event
          .jdaEvent()
          .replyEmbeds(
              Embed.getInfo()
                  .setTitle(Lang.get("music.settings.title"))
                  .setDescription(summary)
                  .build())
          .setEphemeral(true)
          .queue();
      return;
    }
    boolean require = value.equalsIgnoreCase("true");
    var set = settingsService.setActionRequired(uid, key.toLowerCase(), require);
    event
        .jdaEvent()
        .replyEmbeds(
            Embed.getInfo()
                .setTitle(Lang.get("music.settings.title"))
                .setDescription(
                    "Require vote: " + (set.isEmpty() ? "(none)" : String.join(", ", set)))
                .build())
        .setEphemeral(true)
        .queue();
  }
}
