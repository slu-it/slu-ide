# Media player: left for later

Explored, not decided or built. The player itself: [media-player.md](../features/media-player.md).

## OGG, FLAC, Opus
JavaFX cannot play them. With an ffmpeg on the machine the viewer could transcode the file to a temporary WAV and
play that. Open points: finding ffmpeg (the login shell, as Docker's executable is found), the wait for long files,
where the temp file lives and when it goes, and a clear notice when ffmpeg is missing.

## Audio waveform
A waveform instead of the cover icon, or under the progress slider. It needs the samples, so it only works for WAV
without a decoder of our own, or with the transcoding above.

## Remembering the position per file
Reopening a file continues where it stopped. It would go into the project data per file; open points are how long to
keep entries and whether short files (under a minute) are excluded.

## More video containers
`mov`, `webm` and `mkv` are not played by JavaFX. Transcoding would cover them as well, with the same open points.

## Audio-only mp4
An `.mp4` or `.m4v` with no video track shows a black area. Detecting it would switch the view to the audio layout
(cover and tags). `JavaFxPlayback` would have to keep `videoSize` in step with `media.width` and `media.height`:
JavaFX delivers them through `runLater` after READY, so the size is not known when the player becomes ready.
