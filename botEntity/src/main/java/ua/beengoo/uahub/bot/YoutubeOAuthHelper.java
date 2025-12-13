package ua.beengoo.uahub.bot;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper utility to obtain YouTube OAuth refresh token using youtube-source's built-in OAuth flow.
 *
 * This uses YouTube's official TV device OAuth flow which is built into youtube-source.
 * NO Google Cloud Console setup or client credentials needed!
 *
 * Usage:
 * 1. Run this helper class
 * 2. The console will display:
 *    - A URL to visit (like https://www.google.com/device)
 *    - A code to enter on that page
 * 3. Visit the URL and enter the code
 * 4. Sign in with your Google/YouTube account (USE A BURNER ACCOUNT!)
 * 5. Authorize the application
 * 6. The refresh token will appear in the console logs
 * 7. Copy it to your configuration file
 *
 * WARNING: Use a burner/secondary YouTube account, NOT your main account!
 * OAuth usage can potentially lead to account restrictions or termination.
 */
@Slf4j
public class YoutubeOAuthHelper {

    /**
     * Main method to obtain a refresh token using youtube-source's built-in OAuth flow.
     * No parameters needed - everything is handled by the library!
     */
    public static void main(String[] args) {
        log.info("========================================");
        log.info("  YouTube OAuth Token Generator");
        log.info("========================================");
        log.info("|");
        log.info("WARNING: Use a BURNER account, NOT your main YouTube account!");
        log.info("OAuth usage carries risk of account restrictions.");
        log.info("|");
        log.info("========================================");
        log.info("|");

        try {
            // Create a YouTube source manager
            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager();

            log.info("Initiating OAuth flow...");
            log.info("|");
            log.info("IMPORTANT: Watch the console output above this line!");
            log.info("The OAuth instructions should appear with:");
            log.info("  - A URL to visit (e.g., https://www.google.com/device)");
            log.info("  - A code to enter on that page");
            log.info("|");
            log.info("If you don't see the OAuth instructions:");
            log.info("  1. Make sure logging is configured correctly");
            log.info("  2. Set log level for 'dev.lavalink.youtube.http.YoutubeOauth2Handler' to INFO");
            log.info("|");
            log.info("Starting OAuth flow now...");
            log.info("----------------------------------------");
            log.info("|");

            // Start OAuth flow
            // Pass null for refreshToken (we don't have one yet)
            // Pass false for skipInitialization (we want to trigger the OAuth flow)
            youtube.useOauth2(null, false);

            log.info("|");
            log.info("----------------------------------------");
            log.info("|");
            log.info("OAuth flow initiated!");
            log.info("|");
            log.info("Steps to complete:");
            log.info("  1. Look at the log output above");
            log.info("  2. Find the authorization URL and user code");
            log.info("  3. Visit the URL in your browser");
            log.info("  4. Enter the user code shown in the logs");
            log.info("  5. Sign in and authorize with your YouTube account");
            log.info("|");
            log.info("After authorization, your REFRESH TOKEN will appear in the logs.");
            log.info("Look for a line that says something like:");
            log.info("  'Refresh token: YOUR_TOKEN_HERE'");
            log.info("|");
            log.info("Copy that refresh token and add it to your config file:");
            log.info("  youtubeOAuth.enabled = true");
            log.info("  youtubeOAuth.refreshToken = \"YOUR_TOKEN_HERE\"");
            log.info("|");
            log.info("Waiting for you to complete authorization...");
            log.info("(This program will wait for 5 minutes)");
            log.info("|");

            // Wait for the OAuth flow to complete
            // The library handles everything asynchronously
            Thread.sleep(300000); // Wait 5 minutes for user to complete OAuth

            // Try to get the refresh token
            String refreshToken = youtube.getOauth2RefreshToken();

            log.info("|");
            log.info("========================================");
            log.info("|");

            if (refreshToken != null && !refreshToken.isEmpty()) {
                log.info("SUCCESS! Refresh token obtained:");
                log.info("|");
                log.info(refreshToken);
                log.info("|");
                log.info("Add this to your configuration file:");
                log.info("----------------------------------------");
                log.info("youtubeOAuth.enabled = true");
                log.info("youtubeOAuth.refreshToken = \"" + refreshToken + "\"");
                log.info("----------------------------------------");
                log.info("|");
            } else {
                log.info("No refresh token available yet.");
                log.info("|");
                log.info("This could mean:");
                log.info("  - OAuth flow is still in progress");
                log.info("  - Authorization was not completed");
                log.info("  - An error occurred during the flow");
                log.info("|");
                log.info("Check the logs above for:");
                log.info("  - The refresh token (may have been logged already)");
                log.info("  - Any error messages");
                log.info("|");
            }

        } catch (InterruptedException e) {
            System.err.println("\nProcess interrupted.");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("\nError during OAuth flow:");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.err.println();
            System.err.println("Troubleshooting:");
            System.err.println("  1. Ensure you have an internet connection");
            System.err.println("  2. Check that YouTube services are accessible");
            System.err.println("  3. Make sure no firewall is blocking the requests");
            System.err.println("  4. Verify logging is configured to show INFO level");
        }

        log.info("|");
        log.info("========================================");
        log.info("  Additional Information");
        log.info("========================================");
        log.info("|");
        log.info("This OAuth flow uses YouTube's official TV device authorization.");
        log.info("It's the same method used by smart TVs and game consoles.");
        log.info("No Google Cloud Console setup or custom credentials needed!");
        log.info("|");
        log.info("The refresh token never expires (unless manually revoked).");
        log.info("Once obtained, you can use it indefinitely in your bot.");
        log.info("|");
    }

    /**
     * Alternative: Get refresh token from an existing YoutubeAudioSourceManager that
     * has already completed OAuth flow.
     */
    public static String getRefreshTokenFromSource(YoutubeAudioSourceManager youtube) {
        return youtube.getOauth2RefreshToken();
    }
}
