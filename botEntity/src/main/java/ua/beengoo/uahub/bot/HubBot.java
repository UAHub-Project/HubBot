package ua.beengoo.uahub.bot;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import ua.beengoo.uahub.bot.config.ConfigurationFile;
import ua.beengoo.uahub.bot.config.ConfigurationFileCtrl;

/**
 * Application entry point for the UAHub Discord bot.
 *
 * <p>Bootstraps Spring Boot with properties loaded from a JSON configuration file (see {@link
 * ua.beengoo.uahub.bot.config.ConfigurationFile}). This approach avoids committing sensitive or
 * environment-specific values to application.yml.
 */
@SpringBootApplication(scanBasePackages = "ua.beengoo.uahub")
@EnableScheduling
public class HubBot {

  // The reason why I did it like that because Spring Boot need to be initialized after
  // configuration workaround.
  // And also, I don't want users to change some spring boot parameters without forking project, and
  // edit it IN code
  // so this config.json also "safest" way to make configuration without potential risks.
  @Getter
  private static final ConfigurationFile config =
      new ConfigurationFileCtrl("config.json").loadOrCreateDefault();

  /**
   * Starts the Spring Boot application and wires runtime properties from the configuration file.
   *
   * @param args standard program arguments
   */
  public static void main(String[] args) {
    switch (args[0]) {
        case "--utility" -> {
            switch (args[1]) {
                case "yto-helper": YoutubeOAuthHelper.main(args);
            }
        }
        case "--help" -> printHelp();
        default -> initMain(args);
    }
  }

    private static void printHelp() {
        System.out.println("""
            Usage:
                --utility [<utility-name>|list]
            """);
    }

    private static void initMain(String[] args){
      // Workaround to not use application.yaml
      Map<String, Object> props = new HashMap<>();
      props.put("spring.datasource.url", config.postgresqlURL);
      props.put("spring.datasource.username", config.postgresqlUser);
      props.put("spring.datasource.password", config.postgresqlPassword);
      props.put("discord.bot.secret", config.token);
      props.put("discord.guild.id", config.targetGuildId);
      props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");

      props.put("spring.jpa.hibernate.ddl-auto", "update");
      // Disable JPA/hibernate SQL printing; rely on logback for levels
      props.put("spring.jpa.show-sql", "false");
      // Optionally reduce hibernate logger noise via Spring property mapping
      props.put("logging.level.org.hibernate", "WARN");
      props.put("logging.level.org.hibernate.SQL", "OFF");
      props.put("logging.level.org.hibernate.type.descriptor.sql.BasicBinder", "OFF");

      // Apply app locale from config (affects messages bundle selection)
      try {
          java.util.Locale.setDefault(java.util.Locale.forLanguageTag(config.language));
      } catch (Throwable ignored) {
      }

      SpringApplication app = new SpringApplication(HubBot.class);
      app.setDefaultProperties(props);
      app.run(args);
  }
}
