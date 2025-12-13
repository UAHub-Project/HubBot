package ua.beengoo.uahub.bot.module.music.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ua.beengoo.uahub.bot.module.identity.model.ServerMember;
import ua.beengoo.uahub.bot.module.identity.service.ServerMemberController;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylist;
import ua.beengoo.uahub.bot.module.music.model.UserPlaylistTrack;
import ua.beengoo.uahub.bot.module.music.repository.UserPlaylistRepo;
import ua.beengoo.uahub.bot.module.music.repository.UserPlaylistTrackRepo;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

  @Mock private UserPlaylistRepo playlistRepo;
  @Mock private UserPlaylistTrackRepo trackRepo;
  @Mock private ServerMemberController serverMemberController;

  @InjectMocks private PlaylistService svc;

  private final long USER = 123L;

  @BeforeEach
  void setup() {}

  @Test
  @DisplayName("create: throws when playlist exists")
  void createThrowsWhenExists() {
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "MyList"))
        .thenReturn(Optional.of(new UserPlaylist()));
    assertThrows(IllegalArgumentException.class, () -> svc.create(USER, "MyList"));
  }

  @Test
  @DisplayName("create: saves new playlist when not exists")
  void createSavesNew() {
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "MyList"))
        .thenReturn(Optional.empty());
    ServerMember mockMember = new ServerMember();
    when(serverMemberController.addMemberOrNothing(USER)).thenReturn(mockMember);
    when(playlistRepo.save(any(UserPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

    UserPlaylist pl = svc.create(USER, "MyList");
    assertEquals("MyList", pl.getName());
    assertEquals(mockMember, pl.getOwner());
  }

  @Test
  @DisplayName("addTrack: throws when at limit 256")
  void addTrackLimit() {
    UserPlaylist pl = new UserPlaylist();
    pl.setTracks(new ArrayList<>());
    for (int i = 0; i < 256; i++) {
      UserPlaylistTrack t = new UserPlaylistTrack();
      t.setPosition(i);
      t.setQuery("q" + i);
      pl.getTracks().add(t);
    }
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "L")).thenReturn(Optional.of(pl));
    assertThrows(IllegalStateException.class, () -> svc.addTrack(USER, "L", "new", "title"));
  }

  @Test
  @DisplayName("addTrack: throws on duplicate by query (case-insensitive)")
  void addTrackDuplicate() {
    UserPlaylist pl = new UserPlaylist();
    pl.setTracks(new ArrayList<>());
    UserPlaylistTrack t = new UserPlaylistTrack();
    t.setPosition(0);
    t.setQuery("https://x/y");
    pl.getTracks().add(t);
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "L")).thenReturn(Optional.of(pl));
    assertThrows(
        PlaylistService.DuplicateTrackException.class,
        () -> svc.addTrack(USER, "L", "HTTPS://x/y", "title"));
  }

  @Test
  @DisplayName("addTrack: saves track with next position")
  void addTrackSaves() {
    UserPlaylist pl = new UserPlaylist();
    pl.setTracks(new ArrayList<>());
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "L")).thenReturn(Optional.of(pl));
    when(trackRepo.save(any(UserPlaylistTrack.class))).thenAnswer(inv -> inv.getArgument(0));

    svc.addTrack(USER, "L", "q", "t");
    assertEquals(1, pl.getTracks().size());
    UserPlaylistTrack saved = pl.getTracks().iterator().next();
    assertEquals(0, saved.getPosition());
    assertEquals("q", saved.getQuery());
    assertEquals("t", saved.getTitle());
  }

  @Test
  @DisplayName("removeTrack: repacks positions after removal")
  @SuppressWarnings("unchecked")
  void removeTrackRepack() {
    UserPlaylist pl = new UserPlaylist();
    pl.setTracks(new ArrayList<>());
    for (int i = 0; i < 3; i++) {
      UserPlaylistTrack t = new UserPlaylistTrack();
      t.setPosition(i);
      t.setQuery("q" + i);
      pl.getTracks().add(t);
    }
    when(playlistRepo.findByOwnerDiscordIdAndNameIgnoreCase(USER, "L")).thenReturn(Optional.of(pl));
    when(playlistRepo.save(any(UserPlaylist.class))).thenAnswer(inv -> inv.getArgument(0));

    UserPlaylist updated = svc.removeTrack(USER, "L", 1);

    assertEquals(2, updated.getTracks().size());
    // capture saveAll to assert positions
    ArgumentCaptor<List<UserPlaylistTrack>> captor = ArgumentCaptor.forClass(List.class);
    verify(trackRepo).saveAll(captor.capture());
    List<UserPlaylistTrack> repacked = captor.getValue();
    assertEquals(2, repacked.size());
    assertEquals(0, repacked.get(0).getPosition());
    assertEquals("q0", repacked.get(0).getQuery());
    assertEquals(1, repacked.get(1).getPosition());
    assertEquals("q2", repacked.get(1).getQuery());
  }
}
