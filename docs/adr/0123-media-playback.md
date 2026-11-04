# 0123. Media playback with JavaFX Media

- Status: accepted
- Date: 2026-11-04

## Context
Audio files (`.wav`, `.mp3`, ...) and videos should open in an editor tab with the usual controls: play/pause, skip,
a progress slider, times, speed, loop, volume and mute. Thoreau has no player. JavaFX Media (`javafx.media`) is
already in the app, because `javafx.web` requires it: the platform jars carry `libjfxmedia`, gstreamer-lite and the
`libavplugin` bridges to ffmpeg, so using it adds neither a dependency nor size.

## Decision
- **Engine:** `javafx.scene.media` (`Media`, `MediaPlayer`, `MediaView`) in the new module `media`, package
  `dev.sluide.media` (not `previews`, which holds the WebView previews, [ADR 0069](0069-previews-module.md)).
  The views build on the interface `Playback`; `JavaFxPlayback` wraps one file's `MediaPlayer`. Tests use a fake.
- **Claimed extensions:** only the formats JavaFX plays: `mp3`, `wav`, `aif`, `aiff`, `m4a`, `mp4`, `m4v`. `ogg`,
  `flac`, `opus`, raw `aac`, `mov`, `webm`, `mkv` and the like stay as they are.
- **Linux:** MP3, AAC and H.264 decode through the system's ffmpeg libraries (`libavcodec`, `libavformat`); without
  them only WAV and AIFF play. Playing needs an ALSA device (gstreamer-lite's sink); without one every file fails
  with "Could not create player!". A file that cannot be played does not throw: the playback is `FAILED` with a
  readable reason, which the view shows instead of the controls.
- **JavaFX quirks handled in `JavaFxPlayback`:**
  - JavaFX sends the error of any media being opened to every live player and sets it as their `error` without
    stopping them, and which media an error belongs to cannot be told. So an error of a `HALTED` player fails its
    playback; one while loading fails it until the player gets ready after all (another file opened at the same
    time); one after loading is logged and ignored. Accepted limit: a playback's own error that does not halt its
    player is not shown either.
  - At the end the player would stay `PLAYING` and not play again, so it is stopped there and shown as `ENDED` at the
    total time (not while looping: JavaFX calls the end handler before each repetition too). Play starts again from
    0:00. A seek after the end is applied once the player has paused, because a stopped player ignores seeks.
  - JavaFX tells `currentTime` listeners only while playing: after a seek before the first play the time moves later
    without an event, so a seek shows its (clamped) target at once.
  - On macOS JavaFX hands a rate straight to AVPlayer, where a rate above 0 starts playback that JavaFX does not
    notice (it stays `READY`, reports no time). A bound rate did that on open: every file played with the Play button
    showing. AVPlayer's play also resets the rate to 1. So the speed reaches the player only while it plays, and again
    at every start.
- **One playback at a time, app-wide:** `PlaybackCoordinator` (singleton) pauses the previously started playback when
  another one starts, in any window.
- **Volume and mute** are user-wide, in the field `media` of `shared.json` ([ADR 0101](0101-shared-data-file.md)),
  through `MediaPreferences`: read once, written off the FX thread on a `NamedThreads` thread, rapid changes coalesced
  into one write. Position, speed and loop are not stored.

Rejected:
- HTML `<audio>`/`<video>` in a WebView: the same GStreamer backend, with WebKit's unthemed controls.
- `javax.sound` with LGPL decoders (mp3spi, JOrbis, JFLAC): our own player and timing, `java.desktop`, and libraries
  that are no longer maintained; no video.
- Transcoding through an installed ffmpeg (OGG, FLAC, Opus to a temp WAV): possible later, kept as an idea.
- External players: no controls in a tab, and the platform differences move into launching them.

## Consequences
- No new dependency or size; JavaFX's format list limits what opens as media.
- On Linux the user may need the system ffmpeg libraries for MP3 and video. macOS plays them through its own
  frameworks.
- The UI tests load real media: on Linux without a sound card, the media module's test tasks point ALSA at a null
  device ([testing](../testing.md), "Media playback").
