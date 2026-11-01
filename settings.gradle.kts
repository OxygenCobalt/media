// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import androidx.media3.buildlogic.Media3Modules

pluginManagement {
  includeBuild("build-logic-settings")
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("gradlebuild.media3-settings-logic") }

rootProject.name = "androidx.media3"

Media3Modules.EXTERNAL_MODULES.forEach { (gradleName, moduleInfo) ->
  if (moduleInfo.includeInCompositeBuild || gradle.parent == null) {
    include(":$gradleName")
    project(":$gradleName").projectDir = file(moduleInfo.directory)
  }
}

// Auxio normally configures only its runtime modules. Include upstream test infrastructure and its
// dependencies explicitly when running the Media3 tests, without adding work to normal app builds.
if (providers.gradleProperty("media3Tests").isPresent) {
  mapOf(
          "lib-effect" to "effect",
          "lib-effect-ndk" to "effect_ndk",
          "lib-exoplayer-dash" to "exoplayer_dash",
          "lib-inspector" to "inspector",
          "lib-inspector-frame" to "inspector_frame",
          "lib-muxer" to "muxer",
          "lib-transformer" to "transformer",
          "test-data" to "test_data",
          "test-utils" to "test_utils",
          "test-utils-robolectric" to "test_utils_robolectric",
      )
      .forEach { (name, directory) ->
        include(":$name")
        project(":$name").projectDir = file("libraries/$directory")
      }
}
