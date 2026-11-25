/*
 * SPDX-FileCopyrightText: The CyanogenMod Project
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: BSD-3-Clause
 */

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <sys/sysinfo.h>

#define _REALLY_INCLUDE_SYS__SYSTEM_PROPERTIES_H_
#include <sys/_system_properties.h>

using android::base::GetProperty;
using android::base::SetProperty;

constexpr const char* RO_PROP_SOURCES[] = {
    nullptr,
    "odm.",
    "product.",
    "system.",
    "system_ext.",
    "vendor.",
    "vendor_dlkm.",
};

/*
 * SetProperty does not allow updating read only properties and as a result
 * does not work for our use case. Write "OverrideProperty" to do practically
 * the same thing as "SetProperty" without this restriction.
 */
void OverrideProperty(const char* name, const char* value) {
    size_t valuelen = strlen(value);

    prop_info* pi = (prop_info*)__system_property_find(name);
    if (pi != nullptr) {
        __system_property_update(pi, value, valuelen);
    } else {
        __system_property_add(name, strlen(name), value, valuelen);
    }
}

void OverrideDeviceProperties() {
    const auto ro_prop_override = [](const char* source, const char* prop, const char* value,
                                     bool product) {
        std::string prop_name = "ro.";

        if (product) prop_name += "product.";
        if (source != nullptr) prop_name += source;
        if (!product) prop_name += "build.";
        prop_name += prop;

        OverrideProperty(prop_name.c_str(), value);
    };

    std::string prjname = GetProperty("ro.boot.prjname", "");
    if (prjname == "17107") {
        /* OPPO Find X (PAFM00) */
        for (const auto& source : RO_PROP_SOURCES) {
            ro_prop_override(source, "device", "PAFM00", true);
            ro_prop_override(source, "model", "PAFM00", true);
            ro_prop_override(source, "name", "PAFM00", true);
            ro_prop_override(
                source, "fingerprint",
                "OPPO/PAFM00/PAFM00:11/RKQ1.210510.002/1638952815838:user/release-keys",
                true
            );
            ro_prop_override(
                source, "fingerprint",
                "OPPO/PAFM00/PAFM00:11/RKQ1.210510.002/1638952815838:user/release-keys",
                false
            );
        }
    } else if (prjname == "17127") {
        /* OPPO Find X Lamborghini (PAHM00) */
        for (const auto& source : RO_PROP_SOURCES) {
            ro_prop_override(source, "device", "PAHM00", true);
            ro_prop_override(source, "model", "PAHM00", true);
            ro_prop_override(source, "name", "PAHM00", true);
            ro_prop_override(
                source, "fingerprint",
                "OPPO/PAHM00/PAHM00:11/RKQ1.210510.002/1638952815838:user/release-keys",
                true
            );
            ro_prop_override(
                source, "fingerprint",
                "OPPO/PAHM00/PAHM00:11/RKQ1.210510.002/1638952815838:user/release-keys",
                false
            );
        }
    }
}
    
void vendor_load_properties() {
    OverrideDeviceProperties();
}
