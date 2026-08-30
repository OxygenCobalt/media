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
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec

plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.decoder.ffmpeg"

  packaging.jniLibs.useLegacyPackaging = true
  ndkVersion = "28.2.13676358"
}

if (!project.file("src/main/jni/ffmpeg").exists()) {
  throw UnsupportedOperationException("Ffmpeg not found. Did you enable submodules?")
}

android.externalNativeBuild.cmake.path = file("src/main/jni/CMakeLists.txt")
// Should match cmake_minimum_required.
android.externalNativeBuild.cmake.version = "3.21.0+"

val assembleFfmpeg =
  tasks.register<Exec>("assembleFfmpeg") {
    val host =
      when {
        Os.isFamily(Os.FAMILY_MAC) -> "darwin-x86_64"
        Os.isFamily(Os.FAMILY_UNIX) -> "linux-x86_64"
        else ->
          throw UnsupportedOperationException(
            "Building with Windows is not supported. Please use WSL or a unix-based operating system."
          )
    }

    val jniDir = project.file("src/main/jni")
    val libsDir = jniDir.resolve("ffmpeg/android-libs")
    doFirst {
      if (libsDir.exists()) {
        commandLine("true")
      } else {
        val ndkDir = androidComponents.sdkComponents.ndkDirectory.get().asFile
        commandLine(
          jniDir.resolve("build_ffmpeg.sh"),
          project.file("src/main"),
          ndkDir,
          host,
          "21",
          "flac",
          "alac",
        )
      }
    }
  }

tasks.configureEach {
  if (name == "preDebugBuild" || name == "preReleaseBuild") {
    dependsOn(assembleFfmpeg)
  }
}

tasks.withType<Delete>().configureEach {
  if (name == "clean") {
    delete(project.file("src/main/jni/ffmpeg/android-libs"))
  }
}

dependencies {
  implementation(project(":lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
}
