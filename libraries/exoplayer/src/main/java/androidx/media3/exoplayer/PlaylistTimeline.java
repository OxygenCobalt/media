/*
 * Copyright 2020 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.ShuffleOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/** Timeline exposing concatenated timelines of playlist media sources. */
/* package */ final class PlaylistTimeline extends AbstractConcatenatedTimeline {

  private final int windowCount;
  private final int periodCount;
  private final int[] firstPeriodInChildIndices;
  private final int[] firstWindowInChildIndices;
  private final Timeline[] timelines;
  private final Object[] uids;
  private final HashMap<Object, Integer> childIndexByUid;
  private final ShuffleOrder shuffleOrder;

  /** Creates an instance. */
  public PlaylistTimeline(
      Collection<? extends MediaSourceInfoHolder> mediaSourceInfoHolders,
      ShuffleOrder shuffleOrder) {
    this(getTimelines(mediaSourceInfoHolders), getUids(mediaSourceInfoHolders), shuffleOrder);
  }

  private PlaylistTimeline(Timeline[] timelines, Object[] uids, ShuffleOrder shuffleOrder) {
    super(/* isAtomic= */ false, shuffleOrder);
    this.shuffleOrder = shuffleOrder;
    int childCount = timelines.length;
    this.timelines = timelines;
    firstPeriodInChildIndices = new int[childCount];
    firstWindowInChildIndices = new int[childCount];
    this.uids = uids;
    childIndexByUid = new HashMap<>();
    int index = 0;
    int windowCount = 0;
    int periodCount = 0;
    for (Timeline timeline : timelines) {
      this.timelines[index] = timeline;
      firstWindowInChildIndices[index] = windowCount;
      firstPeriodInChildIndices[index] = periodCount;
      windowCount += this.timelines[index].getWindowCount();
      periodCount += this.timelines[index].getPeriodCount();
      childIndexByUid.put(uids[index], index++);
    }
    this.windowCount = windowCount;
    this.periodCount = periodCount;
  }

  private PlaylistTimeline(
      PlaylistTimeline layout, Timeline[] timelines, ShuffleOrder shuffleOrder) {
    super(/* isAtomic= */ false, shuffleOrder);
    this.shuffleOrder = shuffleOrder;
    this.timelines = timelines;
    uids = layout.uids;
    firstPeriodInChildIndices = layout.firstPeriodInChildIndices;
    firstWindowInChildIndices = layout.firstWindowInChildIndices;
    childIndexByUid = layout.childIndexByUid;
    windowCount = layout.windowCount;
    periodCount = layout.periodCount;
  }

  /** Reuses immutable playlist indexing when source updates do not change its structure. */
  public PlaylistTimeline copyWithUpdatedTimelines(
      Collection<? extends MediaSourceInfoHolder> holders, ShuffleOrder shuffleOrder) {
    if (holders.size() != timelines.length) {
      return new PlaylistTimeline(holders, shuffleOrder);
    }
    @Nullable Timeline[] updatedTimelines = null;
    int index = 0;
    for (MediaSourceInfoHolder holder : holders) {
      Timeline childTimeline = holder.getTimeline();
      if (!uids[index].equals(holder.getUid())
          || timelines[index].getWindowCount() != childTimeline.getWindowCount()
          || timelines[index].getPeriodCount() != childTimeline.getPeriodCount()) {
        return new PlaylistTimeline(holders, shuffleOrder);
      }
      if (childTimeline != timelines[index]) {
        if (updatedTimelines == null) {
          updatedTimelines = timelines.clone();
        }
        updatedTimelines[index] = childTimeline;
      }
      index++;
    }
    if (updatedTimelines == null && this.shuffleOrder == shuffleOrder) {
      return this;
    }
    return new PlaylistTimeline(
        this, updatedTimelines != null ? updatedTimelines : timelines, shuffleOrder);
  }

  /** Whether child identities and their window/period offsets are shared with another snapshot. */
  public boolean hasSameLayout(PlaylistTimeline other) {
    return uids == other.uids;
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof PlaylistTimeline) {
      PlaylistTimeline otherTimeline = (PlaylistTimeline) other;
      if (hasSameLayout(otherTimeline) && shuffleOrder == otherTimeline.shuffleOrder) {
        // Comparing unchanged child references avoids allocating concatenated window/period UIDs
        // for the entire playlist when only one source publishes new information.
        return Arrays.equals(timelines, otherTimeline.timelines);
      }
    }
    return super.equals(other);
  }

  /** Returns the child timelines. */
  /* package */ List<Timeline> getChildTimelines() {
    return Arrays.asList(timelines);
  }

  @Override
  protected int getChildIndexByPeriodIndex(int periodIndex) {
    return Util.binarySearchFloor(firstPeriodInChildIndices, periodIndex + 1, false, false);
  }

  @Override
  protected int getChildIndexByWindowIndex(int windowIndex) {
    return Util.binarySearchFloor(firstWindowInChildIndices, windowIndex + 1, false, false);
  }

  @Override
  protected int getChildIndexByChildUid(Object childUid) {
    Integer index = childIndexByUid.get(childUid);
    return index == null ? C.INDEX_UNSET : index;
  }

  @Override
  protected Timeline getTimelineByChildIndex(int childIndex) {
    return timelines[childIndex];
  }

  @Override
  protected int getFirstPeriodIndexByChildIndex(int childIndex) {
    return firstPeriodInChildIndices[childIndex];
  }

  @Override
  protected int getFirstWindowIndexByChildIndex(int childIndex) {
    return firstWindowInChildIndices[childIndex];
  }

  @Override
  protected Object getChildUidByChildIndex(int childIndex) {
    return uids[childIndex];
  }

  @Override
  public int getWindowCount() {
    return windowCount;
  }

  @Override
  public int getPeriodCount() {
    return periodCount;
  }

  /**
   * Creates a copy of the timeline and wraps each child timeline with a {@link ForwardingTimeline}
   * that overrides {@link Timeline#getPeriod(int, Period, boolean)} to set the {@link
   * Period#isPlaceholder} flag.
   *
   * <p>For periods of a live window, the {@link AdPlaybackState} is set to {@link
   * AdPlaybackState#NONE} to allow a live source with ad support to drop the ad playback state.
   *
   * <p>This method should be used when the player is reset (for instance when a playback error
   * occurs or {@link Player#stop()} is called) to make the player resolve the start position like
   * when prepared initially. In this state, each source needs to be prepared again at which point
   * the first timeline delivered by the source will replace the wrapped source to continue
   * playback.
   */
  public PlaylistTimeline copyWithPlaceholderTimeline(ShuffleOrder shuffleOrder) {
    Timeline[] newTimelines = new Timeline[timelines.length];
    for (int i = 0; i < timelines.length; i++) {
      newTimelines[i] =
          new ForwardingTimeline(timelines[i]) {
            private final Window window = new Window();

            @Override
            public Period getPeriod(int periodIndex, Period period, boolean setIds) {
              Period superPeriod = super.getPeriod(periodIndex, period, setIds);
              if (super.getWindow(superPeriod.windowIndex, window).isLive()) {
                // Reset the ad playback state for placeholder period of a live streams.
                superPeriod.set(
                    period.id,
                    period.uid,
                    period.windowIndex,
                    period.durationUs,
                    period.positionInWindowUs,
                    AdPlaybackState.NONE,
                    /* isPlaceholder= */ true);
              } else {
                superPeriod.isPlaceholder = true;
              }
              return superPeriod;
            }
          };
    }
    return new PlaylistTimeline(this, newTimelines, shuffleOrder);
  }

  private static Object[] getUids(
      Collection<? extends MediaSourceInfoHolder> mediaSourceInfoHolders) {
    Object[] uids = new Object[mediaSourceInfoHolders.size()];
    int i = 0;
    for (MediaSourceInfoHolder holder : mediaSourceInfoHolders) {
      uids[i++] = holder.getUid();
    }
    return uids;
  }

  private static Timeline[] getTimelines(
      Collection<? extends MediaSourceInfoHolder> mediaSourceInfoHolders) {
    Timeline[] timelines = new Timeline[mediaSourceInfoHolders.size()];
    int i = 0;
    for (MediaSourceInfoHolder holder : mediaSourceInfoHolders) {
      timelines[i++] = holder.getTimeline();
    }
    return timelines;
  }
}
