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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.ShuffleOrder;
import androidx.media3.test.utils.CapturingAudioSink;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * PCM output tests through the real progressive extractor, audio renderer, and audio processors.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SourcePagingPlaybackTest {

  private static final int SAMPLE_RATE = 44100;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public final ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void adjacentTracksAndUnrelatedEdit_preserveEveryPcmSample() throws Exception {
    byte[] first = pcm(/* firstFrame= */ 0);
    byte[] second = pcm(/* firstFrame= */ SAMPLE_RATE);
    List<MediaItem> items = ImmutableList.of(wav("a.wav", first), wav("b.wav", second));

    Output baseline = play(items, /* useMediaItems= */ false, /* shuffledRepeat= */ false);
    Output paged = play(items, /* useMediaItems= */ true, /* shuffledRepeat= */ false);

    assertThat(paged.pcm).isEqualTo(concatenate(first, second));
    assertThat(paged.pcm).isEqualTo(baseline.pcm);
    assertThat(paged.transitions).containsExactly("a.wav", "b.wav").inOrder();
    assertThat(paged.flushCount).isEqualTo(baseline.flushCount);
    assertThat(paged.bufferingCount).isEqualTo(baseline.bufferingCount);
  }

  @Test
  public void shuffledRepeatAllWrap_preservesSamplesAndNativeSinkLifetime() throws Exception {
    byte[] first = pcm(/* firstFrame= */ 0);
    byte[] second = pcm(/* firstFrame= */ SAMPLE_RATE);
    List<MediaItem> items = ImmutableList.of(wav("a.wav", first), wav("b.wav", second));

    Output baseline = play(items, /* useMediaItems= */ false, /* shuffledRepeat= */ true);
    Output paged = play(items, /* useMediaItems= */ true, /* shuffledRepeat= */ true);

    assertThat(paged.pcm).isEqualTo(concatenate(second, first, second, first));
    assertThat(paged.pcm).isEqualTo(baseline.pcm);
    assertThat(paged.transitions).containsExactly("b.wav", "a.wav", "b.wav", "a.wav").inOrder();
    assertThat(paged.flushCount).isEqualTo(baseline.flushCount);
    assertThat(paged.bufferingCount).isEqualTo(baseline.bufferingCount);
  }

  private Output play(List<MediaItem> items, boolean useMediaItems, boolean shuffledRepeat)
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    PcmRecorder recorder = new PcmRecorder();
    RecordingAudioSink sink = new RecordingAudioSink(context, recorder);
    CapturingRenderersFactory renderers = new CapturingRenderersFactory(context, clock, sink);
    ProgressiveMediaSource.Factory sourceFactory =
        new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(context));
    ExoPlayer player =
        new ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sourceFactory)
            .setClock(clock)
            .build();
    Output output = new Output();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (item != null) {
              output.transitions.add(item.mediaId);
              if (shuffledRepeat && output.transitions.size() == 3) {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
              }
            }
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) {
              output.bufferingCount++;
            }
          }
        });
    try {
      if (useMediaItems) {
        player.setMediaItems(items, shuffledRepeat ? 1 : 0, 0);
      } else {
        List<MediaSource> sources = new ArrayList<>();
        for (MediaItem item : items) {
          sources.add(sourceFactory.createMediaSource(item));
        }
        player.setMediaSources(sources, shuffledRepeat ? 1 : 0, 0);
      }
      if (shuffledRepeat) {
        player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(new int[] {1, 0}, 0));
        player.setShuffleModeEnabled(true);
      }
      player.prepare();
      // Both tracks are buffered before an unrelated queue edit, so destroying a retained successor
      // will be visible as extra work or a changed renderer/sink handoff.
      advance(player).untilFullyBuffered();
      if (useMediaItems) {
        player.addMediaItem(items.get(0));
      } else {
        player.addMediaSource(sourceFactory.createMediaSource(items.get(0)));
      }
      player.removeMediaItem(2);
      if (shuffledRepeat) {
        player.setShuffleOrder(new ShuffleOrder.DefaultShuffleOrder(new int[] {1, 0}, 0));
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
      }
      player.play();
      advance(player).untilState(Player.STATE_ENDED);
    } finally {
      player.release();
    }
    output.pcm = recorder.bytes.toByteArray();
    output.flushCount = sink.flushCount;
    return output;
  }

  private MediaItem wav(String name, byte[] pcm) throws Exception {
    ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    wav.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE);
    wav.putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
    wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
    File file = temporaryFolder.newFile(name);
    try (FileOutputStream stream = new FileOutputStream(file)) {
      stream.write(wav.array());
    }
    return new MediaItem.Builder().setMediaId(name).setUri(Uri.fromFile(file)).build();
  }

  private static byte[] pcm(int firstFrame) {
    ByteBuffer pcm = ByteBuffer.allocate(SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < SAMPLE_RATE; i++) {
      pcm.putShort((short) ((firstFrame + i) * 13));
    }
    return pcm.array();
  }

  private static byte[] concatenate(byte[]... arrays) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (byte[] array : arrays) {
      output.write(array);
    }
    return output.toByteArray();
  }

  private static final class PcmRecorder implements TeeAudioProcessor.AudioBufferSink {
    public final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    public void flush(int sampleRateHz, int channelCount, int encoding) {
      assertThat(sampleRateHz).isEqualTo(SAMPLE_RATE);
      assertThat(channelCount).isEqualTo(1);
      assertThat(encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
      byte[] data = new byte[buffer.remaining()];
      buffer.get(data);
      bytes.write(data, 0, data.length);
    }
  }

  private static final class RecordingAudioSink extends CapturingAudioSink {
    public int flushCount;

    public RecordingAudioSink(Context context, PcmRecorder recorder) {
      super(
          new DefaultAudioSink.Builder(context)
              .setAudioProcessorChain(
                  new DefaultAudioSink.DefaultAudioProcessorChain(new TeeAudioProcessor(recorder)))
              .build(),
          /* shouldCaptureIndividualBuffers= */ false);
    }

    @Override
    public void flush() {
      flushCount++;
      super.flush();
    }
  }

  private static final class Output {
    public byte[] pcm = new byte[0];
    public final List<String> transitions = new ArrayList<>();
    public int flushCount;
    public int bufferingCount;
  }
}
