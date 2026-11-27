/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.livedisplay-service.findx"

#include <aidl/vendor/lineage/livedisplay/BnAntiFlicker.h>
#include <aidl/vendor/lineage/livedisplay/BnSunlightEnhancement.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <string>

using ::aidl::vendor::lineage::livedisplay::BnAntiFlicker;
using ::aidl::vendor::lineage::livedisplay::BnSunlightEnhancement;

namespace {

// Exposed by drivers/gpu/drm/msm/dsi-staging/oppo_display_private_api.c.
// hbm: 0 off, 1..10 send the panel HBM on command (single level).
constexpr const char* kHbmPath = "/sys/kernel/oppo_display/hbm";
// dimlayer_bl_en: below the dimlayer_set_bl threshold, keep the backlight
// fixed and dim through an alpha layer instead (DC dimming).
constexpr const char* kDcDimmingPath = "/sys/kernel/oppo_display/dimlayer_bl_en";

template <typename Base>
class SysfsToggle : public Base {
  public:
    explicit SysfsToggle(const char* path) : path_(path) {}

    ndk::ScopedAStatus getEnabled(bool* _aidl_return) override {
        std::string value;
        int32_t state;
        if (!android::base::ReadFileToString(path_, &value) ||
            !android::base::ParseInt(android::base::Trim(value), &state)) {
            PLOG(ERROR) << "Failed to read " << path_;
            return ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }
        *_aidl_return = state > 0;
        return ndk::ScopedAStatus::ok();
    }

    ndk::ScopedAStatus setEnabled(bool enabled) override {
        // The panel rejects HBM writes while it is off.
        if (!android::base::WriteStringToFile(enabled ? "1" : "0", path_)) {
            PLOG(ERROR) << "Failed to write " << path_;
            return ndk::ScopedAStatus::fromExceptionCode(EX_SERVICE_SPECIFIC);
        }
        return ndk::ScopedAStatus::ok();
    }

  private:
    const char* path_;
};

template <typename T>
void registerService(const std::shared_ptr<T>& service) {
    const std::string instance = std::string(T::descriptor) + "/default";
    binder_status_t status = AServiceManager_addService(service->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK) << "Failed to register " << instance;
}

}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(0);

    registerService(ndk::SharedRefBase::make<SysfsToggle<BnSunlightEnhancement>>(kHbmPath));
    registerService(ndk::SharedRefBase::make<SysfsToggle<BnAntiFlicker>>(kDcDimmingPath));

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // should not reach
}
