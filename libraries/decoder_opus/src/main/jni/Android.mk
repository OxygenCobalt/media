#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

WORKING_DIR := $(call my-dir)
include $(CLEAR_VARS)

# build libopus.a
LOCAL_PATH := $(WORKING_DIR)
include libopus.mk

# build libopusV2JNI.so
include $(CLEAR_VARS)
LOCAL_PATH := $(WORKING_DIR)
LOCAL_MODULE := libopusV2JNI
LOCAL_ARM_MODE := arm
LOCAL_CPP_EXTENSION := .cc
LOCAL_SRC_FILES := opus_jni.cc
LOCAL_LDLIBS := -llog -lz -lm
LOCAL_STATIC_LIBRARIES := libopus
# Enable 16 KB ELF alignment.
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
include $(BUILD_SHARED_LIBRARY)
