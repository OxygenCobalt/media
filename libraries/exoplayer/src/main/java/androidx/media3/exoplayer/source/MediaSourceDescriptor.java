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
package androidx.media3.exoplayer.source;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;

/**
 * Immutable construction information for a source that can be evicted from a playlist and
 * recreated.
 *
 * <p>The source must contain exactly one window and one period, have no ads or live content, and
 * return null from {@link MediaSource#getInitialTimeline()}. Each call to {@link
 * #createMediaSource} must return a new, independent source. Source preparation must synchronously
 * publish its initial single-period timeline, as {@link ProgressiveMediaSource} does; extraction
 * may remain asynchronous.
 *
 * <p>A descriptor captures its construction parameters on the application thread. Creating a source
 * must be safe on the playback thread and must not perform blocking I/O. The descriptor must not
 * retain sources it creates. Metadata queries use the descriptor without accessing a runtime source
 * from the application thread.
 */
@UnstableApi
public interface MediaSourceDescriptor {

  /** Returns the media item. May be called on either player thread. */
  MediaItem getMediaItem();

  /** Creates an unprepared source on the playback thread. */
  MediaSource createMediaSource();

  /**
   * Returns a descriptor with the new item, or null if updating requires replacing the playlist
   * entry.
   *
   * <p>A non-null result guarantees that an existing source supports {@link
   * MediaSource#updateMediaItem} with this item. This method must be side-effect free and safe on
   * either player thread. The returned descriptor retains the captured construction parameters.
   */
  @Nullable
  MediaSourceDescriptor copyWithMediaItem(MediaItem mediaItem);
}
