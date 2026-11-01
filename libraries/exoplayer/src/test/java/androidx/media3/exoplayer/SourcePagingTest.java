/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceDescriptor;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Tests source residency independently of logical playlist membership. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SourcePagingTest {

  private static final long DURATION_US = 10_000_000;
  private static final Format AUDIO_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.AUDIO_RAW)
          .setChannelCount(2)
          .setSampleRate(44100)
          .build();

  private CountingFactory factory;
  private MediaSourceList sourceList;
  private DefaultAllocator allocator;

  @Before
  public void setUp() {
    factory = new CountingFactory();
    sourceList =
        new MediaSourceList(
            () -> {},
            mock(AnalyticsCollector.class),
            Clock.DEFAULT.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null),
            PlayerId.UNSET,
            BandwidthMeter.NO_OP);
    allocator = new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
  }

  @Test
  public void prepare_retainsFullTimelineWithoutCreatingSources() {
    Timeline timeline = setItems(item(0), item(1), item(2));

    sourceList.prepare();

    assertThat(factory.created.get()).isEqualTo(0);
    assertThat(timeline.getWindowCount()).isEqualTo(3);
    assertThat(timeline.getPeriodCount()).isEqualTo(3);
    assertThat(timeline.getWindow(2, new Timeline.Window()).mediaItem).isEqualTo(item(2));
    sourceList.release();
  }

  @Test
  public void bufferedSuccessorAndRepeatedPeriods_keepSourceUntilLastRelease() {
    Timeline timeline = setItems(item(0), item(1), item(2));
    sourceList.prepare();
    MediaPeriod current = createPeriod(timeline, 0, 0);
    MediaPeriod successor = createPeriod(timeline, 1, 1);
    MediaPeriod repeatedSuccessor = createPeriod(timeline, 1, 2);

    sourceList.releasePeriod(current);
    sourceList.releasePeriod(successor);

    assertThat(factory.created.get()).isEqualTo(2);
    assertThat(factory.released.get()).isEqualTo(1);
    assertThat(factory.sources.get(1).isPrepared()).isTrue();
    sourceList.releasePeriod(repeatedSuccessor);
    assertThat(factory.released.get()).isEqualTo(2);
    sourceList.release();
  }

  @Test
  public void evictionAndRecreation_preserveUidResolvedTimelineAndMetadata() {
    Timeline initial = setItems(item(0));
    Object uid = initial.getUidOfPeriod(0);
    sourceList.prepare();
    MediaPeriod period = createPeriod(initial, 0, 0);
    Timeline resolved = sourceList.createTimeline();
    sourceList.releasePeriod(period);
    MediaItem updated =
        item(0)
            .buildUpon()
            .setMediaMetadata(new MediaMetadata.Builder().setTitle("Updated").build())
            .build();

    Timeline updatedTimeline =
        sourceList.updateMediaSourcesWithMediaItems(0, 1, ImmutableList.of(updated));
    MediaPeriod recreated = createPeriod(updatedTimeline, 0, 1);

    assertThat(factory.created.get()).isEqualTo(2);
    assertThat(resolved.getUidOfPeriod(0)).isEqualTo(uid);
    assertThat(sourceList.createTimeline().getUidOfPeriod(0)).isEqualTo(uid);
    assertThat(resolved.getWindow(0, new Timeline.Window()).durationUs).isEqualTo(DURATION_US);
    assertThat(resolved.getWindow(0, new Timeline.Window()).mediaItem).isEqualTo(item(0));
    assertThat(updatedTimeline.getWindow(0, new Timeline.Window()).mediaItem).isEqualTo(updated);
    assertThat(factory.sources.get(1).getMediaItem()).isEqualTo(updated);
    sourceList.releasePeriod(recreated);
    assertThat(sourceList.createTimeline().getWindow(0, new Timeline.Window()).durationUs)
        .isEqualTo(DURATION_US);
    sourceList.release();
  }

  @Test
  public void deletingAnActiveEntry_defersReleaseAndDoesNotRecreateSuccessor() {
    Timeline timeline = setItems(item(0), item(1), item(2));
    sourceList.prepare();
    MediaPeriod current = createPeriod(timeline, 0, 0);
    MediaPeriod successor = createPeriod(timeline, 1, 1);
    Object successorUid = timeline.getUidOfPeriod(1);

    Timeline afterRemoval =
        sourceList.removeMediaSourceRange(0, 1, new ShuffleOrder.DefaultShuffleOrder(2));

    assertThat(afterRemoval.getUidOfPeriod(0)).isEqualTo(successorUid);
    assertThat(factory.created.get()).isEqualTo(2);
    assertThat(factory.released.get()).isEqualTo(0);
    sourceList.releasePeriod(current);
    assertThat(factory.released.get()).isEqualTo(1);
    assertThat(factory.sources.get(1).isPrepared()).isTrue();
    sourceList.releasePeriod(successor);
    sourceList.release();
  }

  @Test
  public void duplicateItemsAndMoves_keepDistinctOccurrenceUids() {
    Timeline timeline = setItems(item(0), item(0), item(1));
    Object first = timeline.getUidOfPeriod(0);
    Object second = timeline.getUidOfPeriod(1);

    Timeline moved = sourceList.moveMediaSource(0, 2, new ShuffleOrder.DefaultShuffleOrder(3));

    assertThat(first).isNotEqualTo(second);
    assertThat(moved.getUidOfPeriod(0)).isEqualTo(second);
    assertThat(moved.getUidOfPeriod(2)).isEqualTo(first);
    assertThat(timeline.getUidOfPeriod(0)).isEqualTo(first);
    assertThat(factory.created.get()).isEqualTo(0);
  }

  @Test
  public void largePlaylistAndDistantSeeks_boundRuntimeSourcesButKeepFullIndices()
      throws Exception {
    ExoPlayer player = createPlayer(new FakeRenderer(C.TRACK_TYPE_AUDIO));
    try {
      List<MediaItem> items = new ArrayList<>();
      for (int i = 0; i < 40_000; i++) {
        items.add(item(i));
      }
      player.setMediaItems(items, /* startIndex= */ 20_000, /* startPositionMs= */ 0);
      assertThat(player.getMediaItemCount()).isEqualTo(40_000);
      assertThat(player.getCurrentMediaItemIndex()).isEqualTo(20_000);
      assertThat(factory.created.get()).isEqualTo(0);
      Object lastUid = player.getCurrentTimeline().getUidOfPeriod(39_999);
      player.prepare();
      advance(player).untilState(Player.STATE_READY);
      for (int index : new int[] {39_999, 0, 17_000, 100, 30_000, 39_999}) {
        player.seekTo(index, 0);
        advance(player).untilPendingCommandsAreFullyHandled();
        advance(player).untilState(Player.STATE_READY);
        assertThat(player.getCurrentMediaItemIndex()).isEqualTo(index);
        assertThat(player.getMediaItemCount()).isEqualTo(40_000);
        assertThat(factory.created.get() - factory.released.get()).isAtMost(101);
      }
      assertThat(player.getCurrentTimeline().getUidOfPeriod(39_999)).isEqualTo(lastUid);
      assertThat(factory.created.get()).isLessThan(1_000);
    } finally {
      player.release();
    }
    assertThat(factory.released.get()).isEqualTo(factory.created.get());
  }

  @Test
  public void shuffledRepeatAllAndUnrelatedEdit_preserveNativeTransitions() throws Exception {
    FakeRenderer renderer = new FakeRenderer(C.TRACK_TYPE_AUDIO);
    ExoPlayer player = createPlayer(renderer);
    List<String> transitions = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (item != null) {
              transitions.add(item.mediaId);
              if (transitions.size() == 5) {
                player.pause();
              }
            }
          }
        });
    try {
      player.setMediaItems(ImmutableList.of(item(0), item(1), item(2)), 1, 0);
      player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(new int[] {1, 2, 0}, 0));
      player.setShuffleModeEnabled(true);
      player.setRepeatMode(Player.REPEAT_MODE_ALL);
      player.prepare();
      advance(player).untilState(Player.STATE_READY);
      // Append and remove an unrelated entry while the successor may already be buffered.
      player.addMediaItem(item(3));
      player.removeMediaItem(3);
      player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(new int[] {1, 2, 0}, 0));
      player.play();
      advance(player).untilPlayWhenReadyIs(false);

      assertThat(transitions).containsExactly("1", "2", "0", "1", "2").inOrder();
      assertThat(player.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ALL);
      assertThat(renderer.enabledCount).isEqualTo(1);
      assertThat(renderer.positionResetCount).isEqualTo(1);
    } finally {
      player.release();
    }
    assertThat(factory.released.get()).isEqualTo(factory.created.get());
  }

  @Test
  public void callerSuppliedSources_useExistingSourcePath() {
    ExoPlayer player = createPlayer(new FakeRenderer(C.TRACK_TYPE_AUDIO));
    try {
      player.setMediaSources(
          ImmutableList.of(
              factory.createMediaSource(item(0)),
              factory.createMediaSource(item(1)),
              factory.createMediaSource(item(2))));
      assertThat(factory.created.get()).isEqualTo(3);
      assertThat(player.getMediaItemCount()).isEqualTo(3);
    } finally {
      player.release();
    }
  }

  @Test
  public void unsupportedFactory_usesExistingFactoryPath() {
    factory.pagingSupported = false;
    ExoPlayer player = createPlayer(new FakeRenderer(C.TRACK_TYPE_AUDIO));
    try {
      player.setMediaItems(ImmutableList.of(item(0), item(1), item(2)));
      assertThat(factory.created.get()).isEqualTo(3);
      assertThat(player.getMediaItemCount()).isEqualTo(3);
    } finally {
      player.release();
    }
  }

  @Test
  public void failedPeriodCreation_releasesSourceAndAllowsRecreation() {
    Timeline timeline = setItems(item(0));
    sourceList.prepare();
    factory.failNextPeriodCreation.set(true);

    assertThrows(IllegalStateException.class, () -> createPeriod(timeline, 0, 0));

    assertThat(factory.created.get()).isEqualTo(1);
    assertThat(factory.released.get()).isEqualTo(1);
    MediaPeriod retry = createPeriod(timeline, 0, 1);
    assertThat(factory.created.get()).isEqualTo(2);
    sourceList.releasePeriod(retry);
    sourceList.release();
    assertThat(factory.released.get()).isEqualTo(2);
  }

  @Test
  public void repeatOneWithPreloading_stopAndReprepare_preservesIdentityAndReleasesSources()
      throws Exception {
    ExoPlayer player = createPlayer(new FakeRenderer(C.TRACK_TYPE_AUDIO));
    AtomicInteger transitions = new AtomicInteger();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (transitions.incrementAndGet() == 4) {
              player.pause();
            }
          }
        });
    try {
      player.setPreloadConfiguration(new ExoPlayer.PreloadConfiguration(2_000_000));
      player.setMediaItems(ImmutableList.of(item(0), item(1)));
      Object uid = player.getCurrentTimeline().getUidOfPeriod(0);
      player.setRepeatMode(Player.REPEAT_MODE_ONE);
      player.prepare();
      player.play();
      advance(player).untilPlayWhenReadyIs(false);
      assertThat(transitions.get()).isEqualTo(4);
      assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
      player.stop();
      advance(player).untilPendingCommandsAreFullyHandled();
      assertThat(factory.released.get()).isEqualTo(factory.created.get());
      player.prepare();
      advance(player).untilState(Player.STATE_READY);
      assertThat(player.getCurrentTimeline().getUidOfPeriod(0)).isEqualTo(uid);
      assertThat(player.getDuration()).isEqualTo(DURATION_US / 1000);
    } finally {
      player.release();
    }
    assertThat(factory.released.get()).isEqualTo(factory.created.get());
  }

  @Test
  public void mixedCommands_matchUnpagedPlayerIncludingImmediateStateAndEvents() throws Exception {
    CountingFactory baselineFactory = new CountingFactory();
    baselineFactory.pagingSupported = false;
    ExoPlayer baseline = createPlayer(baselineFactory, new FakeRenderer(C.TRACK_TYPE_AUDIO));
    ExoPlayer paged = createPlayer(new FakeRenderer(C.TRACK_TYPE_AUDIO));
    List<String> baselineEvents = recordTransitions(baseline);
    List<String> pagedEvents = recordTransitions(paged);
    try {
      for (ExoPlayer player : ImmutableList.of(baseline, paged)) {
        player.setMediaItems(ImmutableList.of(item(0), item(1), item(0), item(2)), 1, 0);
        player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(4, 42));
        player.prepare();
        advance(player).untilState(Player.STATE_READY);
      }
      Random random = new Random(29471);
      for (int operation = 0; operation < 120; operation++) {
        int size = baseline.getMediaItemCount();
        int action = size == 0 ? 0 : random.nextInt(8);
        int index = size == 0 ? 0 : random.nextInt(size);
        int target = size == 0 ? 0 : random.nextInt(size);
        MediaItem newItem = item(random.nextInt(10));
        Consumer<ExoPlayer> command;
        switch (action) {
          case 0:
            command = player -> player.addMediaItem(index, newItem);
            break;
          case 1:
            command = player -> player.removeMediaItem(index);
            break;
          case 2:
            command = player -> player.moveMediaItem(index, target);
            break;
          case 3:
            command = player -> player.seekTo(index, 1000);
            break;
          case 4:
            boolean shuffled = random.nextBoolean();
            command = player -> player.setShuffleModeEnabled(shuffled);
            break;
          case 5:
            int repeat = random.nextInt(3);
            command = player -> player.setRepeatMode(repeat);
            break;
          case 6:
            MediaItem updated =
                baseline
                    .getMediaItemAt(index)
                    .buildUpon()
                    .setMediaMetadata(
                        new MediaMetadata.Builder().setTitle("Edit " + operation).build())
                    .build();
            command = player -> player.replaceMediaItem(index, updated);
            break;
          default:
            command =
                player -> {
                  player.stop();
                  player.prepare();
                };
            break;
        }
        command.accept(baseline);
        command.accept(paged);
        assertSamePlaylistState(baseline, paged);
        for (ExoPlayer player : ImmutableList.of(baseline, paged)) {
          advance(player).untilPendingCommandsAreFullyHandled();
          if (player.getPlaybackState() == Player.STATE_BUFFERING) {
            advance(player).untilState(Player.STATE_READY);
          }
        }
        assertSamePlaylistState(baseline, paged);
        assertThat(pagedEvents).containsExactlyElementsIn(baselineEvents).inOrder();
      }
    } finally {
      baseline.release();
      paged.release();
    }
  }

  private static List<String> recordTransitions(ExoPlayer player) {
    List<String> events = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            events.add((item == null ? "empty" : item.mediaId) + ":" + reason);
          }
        });
    return events;
  }

  private static void assertSamePlaylistState(ExoPlayer baseline, ExoPlayer paged) {
    assertThat(paged.getMediaItemCount()).isEqualTo(baseline.getMediaItemCount());
    assertThat(paged.getCurrentMediaItemIndex()).isEqualTo(baseline.getCurrentMediaItemIndex());
    assertThat(paged.getRepeatMode()).isEqualTo(baseline.getRepeatMode());
    assertThat(paged.getShuffleModeEnabled()).isEqualTo(baseline.getShuffleModeEnabled());
    for (int i = 0; i < baseline.getMediaItemCount(); i++) {
      assertThat(paged.getMediaItemAt(i)).isEqualTo(baseline.getMediaItemAt(i));
      assertThat(paged.getCurrentTimeline().getNextWindowIndex(i, paged.getRepeatMode(), true))
          .isEqualTo(
              baseline.getCurrentTimeline().getNextWindowIndex(i, baseline.getRepeatMode(), true));
    }
  }

  @Test
  public void eagerPreparation_constructsAndPreparesAllSources() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new ExoPlayer.Builder(
                context,
                (handler, video, audio, text, metadata) ->
                    new Renderer[] {new FakeRenderer(C.TRACK_TYPE_AUDIO)},
                factory)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setUseLazyPreparation(false)
            .build();
    try {
      player.setMediaItems(ImmutableList.of(item(0), item(1), item(2)));
      assertThat(factory.created.get()).isEqualTo(3);
      player.prepare();
      advance(player).untilPendingCommandsAreFullyHandled();
      for (FakeMediaSource source : factory.sources) {
        assertThat(source.isPrepared()).isTrue();
      }
    } finally {
      player.release();
    }
    assertThat(factory.released.get()).isEqualTo(3);
  }

  private Timeline setItems(MediaItem... items) {
    List<MediaSourceList.MediaSourceHolder> holders = new ArrayList<>();
    for (MediaItem item : items) {
      holders.add(new MediaSourceList.MediaSourceHolder(factory.createSourceDescriptor(item)));
    }
    return sourceList.setMediaSources(holders, new ShuffleOrder.DefaultShuffleOrder(items.length));
  }

  private MediaPeriod createPeriod(Timeline timeline, int index, long sequenceNumber) {
    return sourceList.createPeriod(
        new MediaSource.MediaPeriodId(timeline.getUidOfPeriod(index), sequenceNumber),
        allocator,
        0);
  }

  private ExoPlayer createPlayer(Renderer renderer) {
    return createPlayer(factory, renderer);
  }

  private ExoPlayer createPlayer(MediaSource.Factory sourceFactory, Renderer renderer) {
    Context context = ApplicationProvider.getApplicationContext();
    return new ExoPlayer.Builder(
            context,
            (handler, video, audio, text, metadata) -> new Renderer[] {renderer},
            sourceFactory)
        .setClock(new FakeClock(/* isAutoAdvancing= */ true))
        .build();
  }

  private static MediaItem item(int index) {
    return new MediaItem.Builder()
        .setMediaId(Integer.toString(index))
        .setUri("content://test/song/" + index)
        .setMediaMetadata(new MediaMetadata.Builder().setTitle("Song " + index).build())
        .build();
  }

  private static final class CountingFactory implements MediaSource.Factory {
    public final AtomicInteger created = new AtomicInteger();
    public final AtomicInteger released = new AtomicInteger();
    public final List<FakeMediaSource> sources = new CopyOnWriteArrayList<>();
    public final AtomicBoolean failNextPeriodCreation = new AtomicBoolean();
    public boolean pagingSupported = true;

    @Override
    public MediaSource createMediaSource(MediaItem item) {
      FakeMediaSource source =
          new FakeMediaSource(
              new SinglePeriodTimeline(DURATION_US, true, false, false, null, item), AUDIO_FORMAT) {
            @Override
            public MediaPeriod createPeriod(
                MediaPeriodId id, Allocator allocator, long startPositionUs) {
              if (failNextPeriodCreation.getAndSet(false)) {
                throw new IllegalStateException("Test period creation failure");
              }
              return super.createPeriod(id, allocator, startPositionUs);
            }

            @Override
            public void releaseSourceInternal() {
              super.releaseSourceInternal();
              released.incrementAndGet();
            }
          };
      source.setCanUpdateMediaItems(true);
      sources.add(source);
      created.incrementAndGet();
      return source;
    }

    @Override
    @Nullable
    public MediaSourceDescriptor createSourceDescriptor(MediaItem item) {
      if (!pagingSupported) {
        return null;
      }
      return new MediaSourceDescriptor() {
        @Override
        public MediaItem getMediaItem() {
          return item;
        }

        @Override
        public MediaSource createMediaSource() {
          return CountingFactory.this.createMediaSource(item);
        }

        @Override
        public MediaSourceDescriptor copyWithMediaItem(MediaItem updated) {
          return createSourceDescriptor(updated);
        }
      };
    }

    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider provider) {
      return this;
    }

    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy policy) {
      return this;
    }

    @Override
    public int[] getSupportedTypes() {
      return new int[] {C.CONTENT_TYPE_OTHER};
    }
  }
}
