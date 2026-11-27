/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.touch-service.findx"

#include <aidl/vendor/lineage/touch/BnTouchscreenGesture.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <mutex>
#include <string>
#include <utility>
#include <vector>

using ::aidl::vendor::lineage::touch::BnTouchscreenGesture;
using ::aidl::vendor::lineage::touch::Gesture;

namespace {

// The S6SY761 driver reports every gesture as KEY_F4 and exposes the type in
// /proc/touchpanel/coordinate. The FindXKeyHandler turns that into scan code
// kGestureStartKey + type, matching the hardware/oplus touch HAL.
constexpr int kGestureStartKey = 246;

// Gesture types from drivers/input/touchscreen/oplus_touchscreen/touchpanel_common.h
// that sec_drivers_s6sy761.c reports. Double tap is handled as tap to wake.
const std::vector<std::pair<int, const char*>> kGestures = {
        {2, "Down arrow"},
        {3, "Up arrow"},
        {4, "Right arrow"},
        {5, "Left arrow"},
        {6, "Letter O"},
        {7, "Two fingers down swipe"},
        {8, "One finger right swipe"},
        {9, "One finger left swipe"},
        {10, "One finger down swipe"},
        {11, "One finger up swipe"},
        {12, "Letter M"},
        {13, "Letter W"},
};

// init enables the touchpanel gesture mode while this or tap to wake is set.
constexpr const char* kGestureEnabledProp = "vendor.touch.gesture.enabled";

class TouchscreenGesture : public BnTouchscreenGesture {
  public:
    ndk::ScopedAStatus getSupportedGestures(std::vector<Gesture>* _aidl_return) override {
        std::vector<Gesture> gestures;
        for (const auto& [type, name] : kGestures) {
            gestures.push_back({static_cast<int>(gestures.size()), name, kGestureStartKey + type});
        }
        *_aidl_return = std::move(gestures);
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setGestureEnabled(const Gesture& gesture, bool enabled) override {
        const int bit = gesture.keycode - kGestureStartKey;
        if (bit < 0 || bit >= 32) {
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        }

        std::lock_guard<std::mutex> lock(mLock);
        if (enabled) {
            mEnabledMask |= 1u << bit;
        } else {
            mEnabledMask &= ~(1u << bit);
        }
        if (!android::base::SetProperty(kGestureEnabledProp, mEnabledMask ? "1" : "0")) {
            LOG(ERROR) << "Failed to set " << kGestureEnabledProp;
            return ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }
        return ndk::ScopedAStatus::ok();
    }

  private:
    std::mutex mLock;
    uint32_t mEnabledMask = 0;
};

}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    auto gesture = ndk::SharedRefBase::make<TouchscreenGesture>();
    const std::string instance = std::string(TouchscreenGesture::descriptor) + "/default";
    binder_status_t status = AServiceManager_addService(gesture->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK) << "Failed to register " << instance;

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
