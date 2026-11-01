Source paging in the Auxio Media3 fork

ExoPlayer automatically pages eligible sources with its default lazy preparation: it keeps the complete
logical playlist while constructing runtime sources only when the native period queue requests them.
There is no separate paging flag. Auxio uses this behavior through its existing progressive source
factory; its queue commands and playback-state model are unchanged. Explicitly disabling lazy
preparation retains eager source construction and preparation.

The first supported factory is `ProgressiveMediaSource.Factory`, for unencrypted `content`, `file`,
`asset`, `rawresource`, and `android.resource` URIs. Other items, factories that do not supply a
descriptor, and caller-supplied `MediaSource` instances follow the existing path. Remote streams,
adaptive manifests, and ads are not newly supported paging cases.

**Ownership and gapless behavior**

Each queue occurrence has its own stable UID, including duplicate media items. A descriptor captures
source-construction settings on the application thread. `MediaSourceList.createPeriod` constructs and
prepares its runtime source on the playback thread, at the same demand boundary used by normal lazy
preparation. ExoPlayer still selects and buffers the successor in advance, including shuffled and
repeat-all successors.

Sources stay resident while any native period uses them. This includes multiple periods for repeat,
buffered successors, preload-pool periods, and periods belonging to an entry removed from the logical
playlist. Releasing the last period permits eviction. There is no fixed item window and no callback
that inserts a successor after the current item changes.

Eviction retains a detached value snapshot of source timing and metadata. It cannot retain the source
through an anonymous timeline wrapper. Recreation preserves external period/window identities and
does not replace known public timing with the recreated source's initial unresolved timeline. Normal
extraction results can update the cached information. Media-item updates preserve the captured factory
settings and are reflected in future source instances.

The native period queue, renderers, audio sink, encoder delay/padding handling, repeat modes, and full
shuffle order remain responsible for playback. A source is never evicted merely because it is outside
a range around the current item.

**Memory and CPU scope**

Runtime source count follows native period demand for eligible entries. MediaItems, descriptors, stable
identities, and resolved timeline information still scale with playlist length. An unsupported item
retains its usual source lifetime. This is not a constant-total-memory playlist or a replacement for
buffer-size control.

Timeline updates reuse immutable UID lookup maps and window/period offsets when the playlist layout is
unchanged. Unchanged timeline references skip application-side snapshot synchronization. Source
updates still scan/copy child-timeline references; actual queue edits can still be linear in playlist
length. The implementation does not claim that all large-playlist ANR causes have been removed.

**Validation**

The fork normally configures only Auxio's runtime modules. `-Pmedia3Tests` enables the upstream test
utilities and their dependencies without changing the normal app build. Use JDK 21 for these
Robolectric tests; the installed Robolectric/ASM version does not support the host's JDK 26 classes.
The Auxio wrapper provides a sufficiently recent Gradle version for this Media3 build:

```sh
# Run from the media directory, with JAVA_HOME pointing to JDK 21.
../gradlew -Pmedia3Tests :lib-exoplayer:testDebugUnitTest \
  --tests androidx.media3.exoplayer.SourcePagingTest \
  --tests androidx.media3.exoplayer.PlaylistTimelineTest \
  --tests androidx.media3.exoplayer.MediaSourceListTest \
  --tests androidx.media3.exoplayer.source.ProgressiveMediaSourceTest \
  --tests androidx.media3.exoplayer.source.MaskingMediaPeriodTest \
  --tests androidx.media3.exoplayer.e2etest.SourcePagingPlaybackTest
```

`SourcePagingTest` covers a 40,000-item logical playlist, distant seeks, duplicate occurrences, multiple
periods per source, active-entry deletion, eviction/recreation, metadata updates, failure cleanup,
repeat-one with preloading, stop/reprepare, unsupported factories, and 120 deterministic operations
compared with a player whose factory does not supply descriptors. It compares immediate public playlist
state and transition events, and checks eager preparation and caller-supplied sources separately.

`SourcePagingPlaybackTest` generates adjacent WAV tracks and checks the exact PCM output against both
the expected sample sequence and a baseline using caller-supplied sources. It also compares audio-sink
flushes and buffering events, including shuffled repeat-all wrap and an unrelated edit with the
successor already buffered.
WAV takes the renderer's raw-audio path, so this checks real sample data rather than a mocked compressed
decoder's output. It does not establish hardware continuity, offload behavior, or decoder-specific
MP3/AAC/FLAC behavior on an Android device.

The upstream `ExoPlayerTest` suite can be split into four smaller processes to limit Robolectric's
accumulated SDK/native-memory footprint. Run this pattern once for each true/false combination:

```sh
../gradlew -Pmedia3Tests :lib-exoplayer:testDebugUnitTest \
  --tests 'androidx.media3.exoplayer.ExoPlayerTest.*[preload=false perStream=false]'
```

Device heap profiling and playback with Auxio's FFmpeg renderer remain necessary to quantify the
production memory savings and verify device-specific audio behavior. The source-lifetime and PCM tests
provide correctness evidence, not an attribution of all OOMs reported against the original PR.
