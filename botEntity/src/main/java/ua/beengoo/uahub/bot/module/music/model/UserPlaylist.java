package ua.beengoo.uahub.bot.module.music.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import ua.beengoo.uahub.bot.module.identity.model.ServerMember;

/** A user-owned playlist persisted with ordered tracks. */
@Entity
@Table(
    name = "music_user_playlists",
    uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "name"}))
public class UserPlaylist {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Getter
  @Setter
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "owner_id")
  @Getter
  @Setter
  private ServerMember owner;

  @Column(name = "name", nullable = false, length = 96)
  @Getter
  @Setter
  private String name;

  @Column(name = "created_at", nullable = false)
  @Getter
  @Setter
  private Instant createdAt = Instant.now();

  @OneToMany(
      mappedBy = "playlist",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.EAGER)
  @OrderBy("position ASC")
  @Getter
  @Setter
  private List<UserPlaylistTrack> tracks = new ArrayList<>();
}
