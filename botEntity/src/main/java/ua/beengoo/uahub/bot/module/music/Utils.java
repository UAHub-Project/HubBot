package ua.beengoo.uahub.bot.module.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

public class Utils {
    public static String getArtwork(AudioTrack track){
        try {
            String src =
                track.getSourceManager() != null ? track.getSourceManager().getSourceName() : null;
            String id = track.getIdentifier();
            if (src != null && src.equalsIgnoreCase("youtube") && id != null && !id.isBlank()) {
                return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
            } else {
                return track.getInfo().artworkUrl;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
