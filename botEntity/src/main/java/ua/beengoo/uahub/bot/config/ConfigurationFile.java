package ua.beengoo.uahub.bot.config;

public class ConfigurationFile {
    public String token = "place your bot token here";
    public String targetGuildId = "970251105558745118";
    public String postgresqlURL = "put";
    public String postgresqlUser = "it";
    public String postgresqlPassword = "here";

    /** Language code for messages bundle, e.g. "en" or "uk". */
    public String language = "en";

    /** YouTube OAuth configuration for authenticated requests */
    public YoutubeOAuthConfig youtubeOAuth = new YoutubeOAuthConfig();

    /** Remote cipher server configuration (optional but recommended) */
    public RemoteCipherConfig remoteCipher = new RemoteCipherConfig();

    public static class YoutubeOAuthConfig {
        /**
         * OAuth2 refresh token obtained from YouTube/Google OAuth flow.
         * This is obtained by completing the OAuth flow once and is used to
         * automatically refresh access tokens.
         */
        public String refreshToken = "";

        /**
         * Enable YouTube OAuth authentication (set to true after obtaining refresh token).
         * OAuth only works with TV and TVHTML5EMBEDDED clients.
         */
        public boolean enabled = false;
    }

    public static class RemoteCipherConfig {
        /** Enable remote cipher server (recommended to avoid cipher extraction errors) */
        public boolean enabled = false;

        /** URL of your remote cipher server (e.g., http://localhost:8001) */
        public String url = "http://localhost:8001";

        /** Password for authenticating with the cipher server */
        public String password = "your_secret_password";
    }
}
