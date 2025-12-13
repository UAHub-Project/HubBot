package ua.beengoo.uahub.bot.helper.paginator;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import ua.beengoo.uahub.bot.Lang;

public class ButtonPaginator {
  private final List<MessageEmbed> pages;
  private final User user;
  private final boolean ephemeral;
    private final List<Buttons> includedButtons;

    private int currentPage = 0;
  private Message message;

  public ButtonPaginator(List<MessageEmbed> pages, User user, boolean ephemeral, List<Buttons> includedButtons) {
      this.pages = pages;
      this.user = user;
      this.ephemeral = ephemeral;
      this.includedButtons = includedButtons;
  }
  public ButtonPaginator(List<MessageEmbed> pages, User user, boolean ephemeral) {
      this.pages = pages;
      this.user = user;
      this.ephemeral = ephemeral;
      this.includedButtons = List.of(Buttons.FIRST, Buttons.PREVIOUS, Buttons.NEXT, Buttons.LAST);
  }

  public void paginate(Message sentMessage, JDA jda) {
    this.message = sentMessage;
    updateMessage();
    jda.addEventListener(listener);
  }

  private void updateMessage() {
    message.editMessageEmbeds(pages.get(currentPage)).setComponents(getButtons()).queue();
  }

  private List<ActionRow> getButtons() {

      List<Button> buttons = new ArrayList<>(List.of());

      includedButtons.forEach((a) -> {
          switch (a) {
              case FIRST -> buttons.add(Button.primary("first", Lang.get("paginator.first"))
                  .withDisabled(currentPage == 0));
              case PREVIOUS -> buttons.add(Button.primary("prev", Lang.get("paginator.prev"))
                  .withDisabled(currentPage == 0));
              case NEXT -> buttons.add(Button.primary("next", Lang.get("paginator.next"))
                  .withDisabled(currentPage == pages.size() - 1));
              case LAST -> buttons.add(Button.primary("last", Lang.get("paginator.last"))
                  .withDisabled(currentPage == pages.size() - 1));
          }
      });

    return List.of(ActionRow.of(buttons));
  }

  private final ListenerAdapter listener =
      new ListenerAdapter() {
        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
          if (!event.getMessageId().equals(message.getId())) return;
          if (!event.getUser().equals(user)) {
            event.deferEdit().queue(); // ігнор для інших
            return;
          }

          switch (event.getComponentId()) {
            case "first" -> currentPage = 0;
            case "prev" -> currentPage = Math.max(0, currentPage - 1);
            case "next" -> currentPage = Math.min(pages.size() - 1, currentPage + 1);
            case "last" -> currentPage = pages.size() - 1;
          }

          event.editMessageEmbeds(pages.get(currentPage)).setComponents(getButtons()).queue();
        }
      };
}
