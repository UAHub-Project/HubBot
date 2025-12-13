package ua.beengoo.uahub.bot.module.music.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.beengoo.uahub.bot.module.identity.model.ServerMember;
import ua.beengoo.uahub.bot.module.identity.service.ServerMemberController;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylist;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylistTrack;
import ua.beengoo.uahub.bot.module.music.repository.UserPlaylistRepo;
import ua.beengoo.uahub.bot.module.music.repository.UserPlaylistTrackRepo;

@Service
public class PlaylistService {
  private static final int MAX_TRACKS = 256;
  private final UserPlaylistRepo playlistRepo;
  private final UserPlaylistTrackRepo trackRepo;
  private final ServerMemberController serverMemberController;

  public PlaylistService(
      UserPlaylistRepo playlistRepo,
      UserPlaylistTrackRepo trackRepo,
      ServerMemberController serverMemberController) {
    this.playlistRepo = playlistRepo;
    this.trackRepo = trackRepo;
    this.serverMemberController = serverMemberController;
  }

  /** Creates a new playlist for the owner. */
  @Transactional
  public UserPlaylist create(long ownerDiscordId, String name) {
    playlistRepo
        .findByOwnerDiscordIdAndNameIgnoreCase(ownerDiscordId, name)
        .ifPresent(
            pl -> {
              throw new IllegalArgumentException("Playlist with this name already exists");
            });
    ServerMember owner = serverMemberController.addMemberOrNothing(ownerDiscordId);
    UserPlaylist pl = new UserPlaylist();
    pl.setOwner(owner);
    pl.setName(name);
    return playlistRepo.save(pl);
  }

  /** Deletes a playlist by name. */
  @Transactional
  public void delete(long ownerDiscordId, String name) {
    UserPlaylist pl = get(ownerDiscordId, name);
    playlistRepo.delete(pl);
  }

  /** Returns a playlist by name or throws if missing. */
  @Transactional(readOnly = true)
  public UserPlaylist get(long ownerDiscordId, String name) {
    return playlistRepo
        .findByOwnerDiscordIdAndNameIgnoreCase(ownerDiscordId, name)
        .orElseThrow(() -> new IllegalArgumentException("Playlist not found"));
  }

  /** Lists user playlists ordered by name. */
  @Transactional(readOnly = true)
  public List<UserPlaylist> list(long ownerDiscordId) {
    return playlistRepo.findByOwnerDiscordIdOrderByNameAsc(ownerDiscordId);
  }

  /** Appends a track to a playlist; enforces max size. */
  @Transactional
  public UserPlaylist addTrack(long ownerDiscordId, String name, String query, String title) {
    UserPlaylist pl = get(ownerDiscordId, name);
    int size = pl.getTracks().size();
    if (size >= MAX_TRACKS) throw new IllegalStateException("Max 256 tracks");
    // Prevent duplicates by query (case-insensitive)
    boolean exists =
        pl.getTracks().stream()
            .anyMatch(t -> t.getQuery() != null && t.getQuery().equalsIgnoreCase(query));
    if (exists) throw new DuplicateTrackException();
    UserPlaylistTrack t = new UserPlaylistTrack();
    t.setPlaylist(pl);
    t.setPosition(size);
    t.setQuery(query);
    t.setTitle(title);
    trackRepo.save(t);
    pl.getTracks().add(t);
    return pl;
  }

  public static class DuplicateTrackException extends RuntimeException {}

  /** Removes a track at 0-based position and repacks positions. */
  @Transactional
  public UserPlaylist removeTrack(long ownerDiscordId, String name, int index0based) {
    UserPlaylist pl = get(ownerDiscordId, name);
    if (index0based < 0 || index0based >= pl.getTracks().size()) return pl;
    List<UserPlaylistTrack> arr = new ArrayList<>(pl.getTracks());
    UserPlaylistTrack rem = arr.get(index0based);
    trackRepo.delete(rem);
    // Re-pack positions
    arr.remove(index0based);
    for (int i = 0; i < arr.size(); i++) {
      arr.get(i).setPosition(i);
    }
    trackRepo.saveAll(arr);
    pl.setTracks(new ArrayList<>(arr));
    return playlistRepo.save(pl);
  }
}
