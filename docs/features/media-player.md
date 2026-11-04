# Media player

**Module:** `media`: `MediaFileViewer` and `MediaFileView`, built from the `Playback` engine (`JavaFxPlayback`), the
`MediaControlBar`, the `MediaStage`, the `PlaybackCoordinator` and `MediaPreferences`; decision in
[ADR 0123](../adr/0123-media-playback.md). What is left out: [media-formats.md](../ideas/media-formats.md).

Audio and video files open in an editor tab, paused at 0:00, with the view focused so that the keys work at once.
There is no size limit; the player streams from the file.

## Formats
Only what JavaFX Media plays is claimed: audio `mp3`, `wav`, `aif`, `aiff`, `m4a`; video `mp4`, `m4v` (case
does not matter). `ogg`, `flac`, `opus`, raw `aac`, `mov`, `webm` and `mkv` stay as they are, because JavaFX cannot
play them. A claimed file that cannot be played anyway (corrupt, or a codec the machine lacks) shows its name, size and
"Cannot play <name>: <reason>" instead of the controls, and its tab gets the failure marker.

## Layout and controls
- Center: the video, fitted to the area with its aspect ratio (black around it in every theme), or for audio the cover
  art with title, artist and album from the tags. Missing tags fall back to the file name, a missing cover to a large
  music-note icon.
- Below: the control bar (Back 10 s, Play/Pause, Ahead 10 s, current time, progress slider, total time, speed, loop,
  mute, volume slider), then a status line such as `MP3 · 4.2 MB` or `MP4 · 12.0 MB · 1920 × 1080`.
- At the end playback stops at the total time; Play starts again from 0:00, unless loop is on. Speed and loop are
  not kept; each tab starts at 1× without loop.

## Keys
Space, Left/Right, Up/Down and M, while the view has the focus ([keyboard-shortcuts.md](keyboard-shortcuts.md)).
There are no global bindings. Clicking anywhere in the view focuses it, so the keys work again.

## One playback at a time
Starting playback pauses the one started before, in every window (`PlaybackCoordinator`). A Read Aloud reading takes
part: starting it pauses a playing medium and the other way round ([read-aloud.md](read-aloud.md)).

## Background play
A playing tab keeps playing when another tab is selected, and shows the running marker on its tab while it plays
(`activity`, [ui-conventions.md](../ui-conventions.md)). Closing the tab stops it without asking.

## Volume and mute
Volume and mute are user-wide, in the field `media` of `~/.sluide/shared.json` (`volume`, `muted`). A view reads them
when it opens; open tabs keep their own values until they are reopened (a reload keeps them too). Changes are saved
off the FX thread, coalesced, and flushed when the app quits. Applying the stored values on open does not save.

## Reload and move
- The file changed on disk: the view builds a new player and continues at the old position (kept within the new
  length), playing again if it was playing (that counts as a start, so it pauses another playing tab). Volume and
  mute of the tab carry over. An event that left size and modification time as they were does nothing.
- The file is moved or renamed: the editor replaces the view, so playback stops.

## Known limits
- Linux plays MP3, AAC (m4a) and H.264 (mp4) only through the system's ffmpeg libraries (`libavcodec`,
  `libavformat`); without them only WAV and AIFF play and the others show the failure notice. Playing also needs an
  ALSA device.
- An audio-only `.mp4` shows the black video area with the controls, not the cover layout.
- A player's own error after loading that does not halt it is not shown (ADR 0123).
- A rewrite that keeps the size within the file system's time granularity (1 s on HFS+, 2 s on FAT) is not noticed,
  e.g. an in-place tag edit; reopen the file.
- Audible output, MP3/M4A/MP4 and the macOS path are not covered by the automated tests, which generate WAV files
  ([testing.md](../testing.md), "Media playback").
