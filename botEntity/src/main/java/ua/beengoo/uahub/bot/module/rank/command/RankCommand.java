package ua.beengoo.uahub.bot.module.rank.command;

import com.github.kaktushose.jda.commands.annotations.interactions.Command;
import com.github.kaktushose.jda.commands.annotations.interactions.Interaction;
import com.github.kaktushose.jda.commands.dispatching.events.interactions.CommandEvent;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import ua.beengoo.uahub.bot.ContextHolder;
import ua.beengoo.uahub.bot.Lang;
import ua.beengoo.uahub.bot.helper.paginator.ButtonPaginator;
import ua.beengoo.uahub.bot.helper.paginator.Buttons;
import ua.beengoo.uahub.bot.layout.message.Embed;
import ua.beengoo.uahub.bot.module.identity.service.ServerMemberController;
import ua.beengoo.uahub.bot.module.permissions.service.PermissionService;
import ua.beengoo.uahub.bot.module.rank.model.RankStats;
import ua.beengoo.uahub.bot.module.rank.service.RankingService;

@Interaction
/** Slash command: displays rank information and extended voice-time stats for the caller. */
public class RankCommand {

  private final ServerMemberController serverMemberController;
  private final PermissionService permissionService;
  private final RankingService rankingService;

  public RankCommand() {
    serverMemberController = ContextHolder.getBean(ServerMemberController.class);
    permissionService = ContextHolder.getBean(PermissionService.class);
    rankingService = ContextHolder.getBean(RankingService.class);
  }

  /** Handler for the /rank command. */
  @Command(value = "rank", desc = "Інформація про очки активності на севрері")
  public void onRankExec(CommandEvent event) {
    boolean allowed =
        permissionService.has(
            event.getUser().getIdLong(),
            event.getMember() != null
                ? event.getMember().getRoles().stream().map(Role::getIdLong).toList()
                : null,
            "rank.view");
    if (!allowed) {
      event.reply(
          Embed.getError()
              .setTitle(Lang.get("perms.error.insufficient_rights.title"))
              .setDescription(Lang.get("ranking.view.required")));
      return;
    }
    // Update voice pool stats on-demand for fresher data
    rankingService.updateVoiceRankState();

    RankStats member =
        serverMemberController.addMemberOrNothing(event.getUser().getIdLong()).getRankStats();

    // Compute positions in chat/voice leaderboards
    List<RankStats> allStats = new ArrayList<>();
    serverMemberController.getAll().forEach(sm -> allStats.add(sm.getRankStats()));
    long chatPos = getPosition(allStats, member, RankStats::getChatPoints);
    long voicePos = getPosition(allStats, member, RankStats::getVoicePoints);
    long primePos = getPosition(allStats, member, RankStats::getPrimePoints);

    List<MessageEmbed> pages = new ArrayList<>();

    // Page 1: Existing rank summary
    EmbedBuilder page1 =
        Embed.getInfo()
            .setTitle(Lang.get("ranking.rank.title.self"))
            .setThumbnail(event.getUser().getAvatarUrl())
            .setDescription(
                Lang.get("ranking.rank.desc")
                    .formatted(
                        (int) (member.getLevel()),
                        RankingService.getPointsToNextLevel(member),
                        RankingService.getLevelProgress(member),
                        member.getChatPoints(),
                        chatPos,
                        member.getVoicePoints(),
                        voicePos,
                        member.getPrimePoints(),
                        primePos,
                        member.getMemberMultiplier()));
    pages.add(page1.build());

    // Page 2: Extended stats
    String extTitle = Lang.get("ranking.rank.ext.title");
    EmbedBuilder page2 =
        Embed.getInfo().setTitle(extTitle).setThumbnail(event.getUser().getAvatarUrl());

    long msAlone = member.getVoiceMsAlone();
    long msWithOthers = member.getVoiceMsWithOthers();
    long msMuted = member.getVoiceMsMuted();
    long msDeaf = member.getVoiceMsDeafened();
    long msActive = member.getVoiceMsActive();
    long msTotal = member.getVoiceMsTotal();
    long msInactive = Math.max(0, msTotal - msActive);
    double activeRatio = member.getActiveVoiceRatio() * 100.0;

    StringBuilder sb = new StringBuilder();
    sb.append(Lang.get("ranking.rank.ext.messages_sent"))
        .append(member.getMessagesSent())
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_joins"))
        .append(member.getVoiceJoins())
        .append("`\n\n")
        .append(Lang.get("ranking.rank.ext.voice_time_total"))
        .append(formatDuration(msTotal))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_alone"))
        .append(formatDuration(msAlone))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_with_others"))
        .append(formatDuration(msWithOthers))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_muted"))
        .append(formatDuration(msMuted))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_deafened"))
        .append(formatDuration(msDeaf))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_active"))
        .append(formatDuration(msActive))
        .append("`\n")
        .append(Lang.get("ranking.rank.ext.voice_time_inactive"))
        .append(formatDuration(msInactive))
        .append("`\n\n")
        .append(Lang.get("ranking.rank.ext.active_ratio"))
        .append(String.format("%.1f%%", activeRatio))
        .append("`");

    page2.setDescription(sb.toString());
    pages.add(page2.build());

    // Send first page and paginate with buttons
    event
        .jdaEvent()
        .replyEmbeds(pages.getFirst())
        .setEphemeral(false)
        .queue(
            s ->
                s.retrieveOriginal()
                    .queue(
                        sentMsg ->
                            new ButtonPaginator(pages, event.getUser(), false, List.of(Buttons.PREVIOUS, Buttons.NEXT))
                                .paginate(sentMsg, event.getJDA())));
  }

  private long getPosition(
      List<RankStats> all, RankStats me, java.util.function.ToDoubleFunction<RankStats> metric) {
    double myValue = metric.applyAsDouble(me);
    long better = all.stream().filter(s -> metric.applyAsDouble(s) > myValue).count();
    return better + 1; // 1-based position; ties share same position
  }

  private String formatDuration(long ms) {
    long totalSeconds = Math.max(0, ms / 1000);
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    if (hours > 0) {
      return String.format("%dh %02dm %02ds", hours, minutes, seconds);
    } else if (minutes > 0) {
      return String.format("%dm %02ds", minutes, seconds);
    } else {
      return String.format("%ds", seconds);
    }
  }
}
