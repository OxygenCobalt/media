/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.buildlogic

object Media3Modules {
  val EXTERNAL_MODULES: Map<String, Media3Module> =
    mapOf(
      // Keep this list limited to the modules vendored by Auxio. Every entry is configured by
      // Gradle, so adding unused modules here significantly increases configuration time.
      // go/keep-sorted start
      "lib-common" to
        Media3Module("libraries/common", "media3-common", "Media3 common module", allowKt = false),
      "lib-common-ktx" to
        Media3Module("libraries/common_ktx", "media3-common-ktx", "Media3 common KTX module"),
      "lib-container" to
        Media3Module(
          "libraries/container",
          "media3-container",
          "Media3 Container module",
          allowKt = false,
        ),
      "lib-database" to
        Media3Module(
          "libraries/database",
          "media3-database",
          "Media3 database module",
          allowKt = false,
        ),
      "lib-datasource" to
        Media3Module(
          "libraries/datasource",
          "media3-datasource",
          "Media3 DataSource module",
          allowKt = false,
        ),
      "lib-decoder" to
        Media3Module(
          "libraries/decoder",
          "media3-decoder",
          "Media3 decoder module",
          allowKt = false,
        ),
      "lib-decoder-ffmpeg" to
        Media3Module(
          "libraries/decoder_ffmpeg",
          "media3-decoder-ffmpeg",
          "Media3 FFmpeg decoder module",
        ),
      "lib-decoder-midi" to
        Media3Module(
          "libraries/decoder_midi",
          "media3-exoplayer-midi",
          "Media3 MIDI decoder module",
          includeInCompositeBuild = false,
        ),
      "lib-exoplayer" to
        Media3Module(
          "libraries/exoplayer",
          "media3-exoplayer",
          "Media3 ExoPlayer module",
          allowKt = false,
        ),
      "lib-extractor" to
        Media3Module(
          "libraries/extractor",
          "media3-extractor",
          "Media3 Extractor module",
          allowKt = false,
        ),
      // go/keep-sorted end
    )
}
