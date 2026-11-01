/*
 * Copyright 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link PlaylistTimeline}. */
@RunWith(AndroidJUnit4.class)
public class PlaylistTimelineTest {

  @Test
  public void sourceRefresh_preservesOldSnapshotAndMatchesRebuiltTimeline() {
    MediaSourceInfoHolder first = mock(MediaSourceInfoHolder.class);
    MediaSourceInfoHolder second = mock(MediaSourceInfoHolder.class);
    when(first.getUid()).thenReturn("first");
    when(second.getUid()).thenReturn("second");
    when(first.getTimeline()).thenReturn(singlePeriod(100));
    when(second.getTimeline()).thenReturn(singlePeriod(200));
    List<MediaSourceInfoHolder> holders = ImmutableList.of(first, second);
    ShuffleOrder order = new ShuffleOrder.DefaultShuffleOrder(2, 0);
    PlaylistTimeline original = new PlaylistTimeline(holders, order);
    assertThat(original.copyWithUpdatedTimelines(holders, order)).isSameInstanceAs(original);

    when(first.getTimeline()).thenReturn(singlePeriod(300));
    PlaylistTimeline updated = original.copyWithUpdatedTimelines(holders, order);
    PlaylistTimeline rebuilt = new PlaylistTimeline(holders, order);

    assertThat(original.getWindow(0, new Timeline.Window()).durationUs).isEqualTo(100);
    assertThat(updated.getWindow(0, new Timeline.Window()).durationUs).isEqualTo(300);
    assertThat(updated.getUidOfPeriod(1)).isEqualTo(original.getUidOfPeriod(1));
    assertThat(updated).isEqualTo(rebuilt);
    assertThat(rebuilt).isEqualTo(updated);
    assertThat(updated.hashCode()).isEqualTo(rebuilt.hashCode());

    // A new but equal source timeline must not become an observable playlist change.
    when(first.getTimeline()).thenReturn(singlePeriod(300));
    PlaylistTimeline equivalent = updated.copyWithUpdatedTimelines(holders, order);
    assertThat(equivalent).isEqualTo(updated);
    assertThat(equivalent.hashCode()).isEqualTo(updated.hashCode());
  }

  @Test
  public void sourceWindowCountChange_rebuildsOffsetsWithoutChangingUnaffectedUids() {
    MediaSourceInfoHolder first = mock(MediaSourceInfoHolder.class);
    MediaSourceInfoHolder second = mock(MediaSourceInfoHolder.class);
    when(first.getUid()).thenReturn("first");
    when(second.getUid()).thenReturn("second");
    when(first.getTimeline()).thenReturn(singlePeriod(100));
    when(second.getTimeline()).thenReturn(singlePeriod(200));
    List<MediaSourceInfoHolder> holders = ImmutableList.of(first, second);
    ShuffleOrder order = new ShuffleOrder.DefaultShuffleOrder(2, 0);
    PlaylistTimeline original = new PlaylistTimeline(holders, order);

    when(first.getTimeline()).thenReturn(new FakeTimeline(2));
    PlaylistTimeline updated = original.copyWithUpdatedTimelines(holders, order);

    assertThat(updated.getWindowCount()).isEqualTo(3);
    assertThat(updated.getUidOfPeriod(2)).isEqualTo(original.getUidOfPeriod(1));
    assertThat(updated).isEqualTo(new PlaylistTimeline(holders, order));
    assertThat(original.getWindowCount()).isEqualTo(2);
  }

  private static Timeline singlePeriod(long durationUs) {
    return new SinglePeriodTimeline(durationUs, true, false, false, null, MediaItem.EMPTY);
  }

  @Test
  public void copyWithPlaceholderTimeline_equalTimelineExceptPlaceholderFlag() {
    MediaSourceInfoHolder mediaSourceInfoHolder1 = mock(MediaSourceInfoHolder.class);
    MediaSourceInfoHolder mediaSourceInfoHolder2 = mock(MediaSourceInfoHolder.class);
    ImmutableList<MediaSourceInfoHolder> mediaSourceInfoHolders =
        ImmutableList.of(mediaSourceInfoHolder1, mediaSourceInfoHolder2);
    FakeTimeline fakeTimeline1 = new FakeTimeline(2);
    FakeTimeline fakeTimeline2 = new FakeTimeline(1);
    when(mediaSourceInfoHolder1.getTimeline()).thenReturn(fakeTimeline1);
    when(mediaSourceInfoHolder1.getUid()).thenReturn("uid1");
    when(mediaSourceInfoHolder2.getTimeline()).thenReturn(fakeTimeline2);
    when(mediaSourceInfoHolder2.getUid()).thenReturn("uid2");
    ShuffleOrder.DefaultShuffleOrder shuffleOrder =
        new ShuffleOrder.DefaultShuffleOrder(mediaSourceInfoHolders.size());
    PlaylistTimeline playlistTimeline = new PlaylistTimeline(mediaSourceInfoHolders, shuffleOrder);

    PlaylistTimeline playlistTimelineCopy =
        playlistTimeline.copyWithPlaceholderTimeline(shuffleOrder);

    assertThat(playlistTimelineCopy).isNotEqualTo(playlistTimeline);
    assertThat(playlistTimelineCopy.getWindowCount()).isEqualTo(playlistTimeline.getWindowCount());
    assertThat(playlistTimelineCopy.getPeriodCount()).isEqualTo(playlistTimeline.getPeriodCount());
    List<Timeline> copiedChildTimelines = playlistTimelineCopy.getChildTimelines();
    List<Timeline> originalChildTimelines = playlistTimeline.getChildTimelines();
    for (int i = 0; i < copiedChildTimelines.size(); i++) {
      Timeline childTimeline = copiedChildTimelines.get(i);
      Timeline originalChildTimeline = originalChildTimelines.get(i);
      for (int j = 0; j < childTimeline.getWindowCount(); j++) {
        assertThat(childTimeline.getWindow(/* windowIndex= */ 0, new Timeline.Window()))
            .isEqualTo(
                originalChildTimeline.getWindow(/* windowIndex= */ 0, new Timeline.Window()));
        Timeline.Period expectedPeriod =
            originalChildTimeline.getPeriod(/* periodIndex= */ 0, new Timeline.Period());
        Timeline.Period actualPeriod =
            childTimeline.getPeriod(/* periodIndex= */ 0, new Timeline.Period());
        assertThat(actualPeriod).isNotEqualTo(expectedPeriod);
        actualPeriod.isPlaceholder = false;
        assertThat(actualPeriod).isEqualTo(expectedPeriod);
      }
    }
  }
}
