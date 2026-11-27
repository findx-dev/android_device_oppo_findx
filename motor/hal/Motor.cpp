/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "FindXCameraMotorHal"

#include "Motor.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <unistd.h>

#include <cerrno>
#include <string>

namespace aidl::vendor::oplus::hardware::motor {
namespace {

// Exposed by drivers/misc/oppo/oppo_motor.c (CONFIG_MOTOR_CLASS_INTERFACE).
constexpr const char* kDirectionPath = "/sys/class/motor/direction";
constexpr const char* kEnablePath = "/sys/class/motor/enable";
constexpr const char* kPositionPath = "/sys/class/motor/position";
constexpr const char* kMoveStatePath = "/sys/class/motor/move_state";
constexpr const char* kCalibrationPath = "/sys/class/motor/hall_calibration";

// Hall thresholds the stock framework (OppoMotorCalibrateHelper, projects
// 17107/17127) writes on every boot; also motor.hall.default.cali.data in
// /odm/etc/engineermode/features. Order: hall0 irq, hall1 irq, down retard
// hall0/hall1, up retard hall0/hall1. The driver ignores non-positive values.
constexpr const char* kCalibration = "170,170,480,0,0,480";

// enum motor_move_state in oppo_motor.h.
constexpr int32_t kMoveStateUpward = 1;
constexpr int32_t kMoveStateDownward = 2;

constexpr useconds_t kStopPollIntervalMicros = 10'000;
constexpr int kStopPollAttempts = 150;

ndk::ScopedAStatus errnoStatus(const std::string& what) {
    const int error = errno == 0 ? EIO : errno;
    LOG(ERROR) << what << ": " << strerror(error);
    return ndk::ScopedAStatus::fromServiceSpecificErrorWithMessage(error, what.c_str());
}

ndk::ScopedAStatus illegalArgument(const char* message) {
    return ndk::ScopedAStatus::fromExceptionCodeWithMessage(EX_ILLEGAL_ARGUMENT, message);
}

bool writeNode(const char* path, const std::string& value) {
    errno = 0;
    return android::base::WriteStringToFile(value, path);
}

bool readNode(const char* path, int32_t* value) {
    std::string text;
    errno = 0;
    if (!android::base::ReadFileToString(path, &text)) {
        return false;
    }
    if (!android::base::ParseInt(android::base::Trim(text), value)) {
        errno = EPROTO;
        return false;
    }
    return true;
}

int32_t movingDirection(int32_t moveState) {
    if (moveState == kMoveStateUpward) return IMotor::DIRECTION_UP;
    if (moveState == kMoveStateDownward) return IMotor::DIRECTION_DOWN;
    return -1;
}

bool waitUntilStopped() {
    for (int attempt = 0; attempt < kStopPollAttempts; ++attempt) {
        int32_t moveState;
        if (!readNode(kMoveStatePath, &moveState)) return false;
        if (movingDirection(moveState) < 0) return true;
        usleep(kStopPollIntervalMicros);
    }
    errno = ETIMEDOUT;
    return false;
}

}  // namespace

ndk::ScopedAStatus Motor::initializeCalibration() {
    std::lock_guard<std::mutex> lock(mLock);
    return initializeCalibrationLocked();
}

ndk::ScopedAStatus Motor::initializeCalibrationLocked() {
    if (mCalibrationInitialized) {
        return ndk::ScopedAStatus::ok();
    }
    if (!writeNode(kCalibrationPath, kCalibration)) {
        return errnoStatus(std::string("write ") + kCalibrationPath);
    }
    mCalibrationInitialized = true;
    LOG(INFO) << "Hall calibration set to " << kCalibration;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Motor::move(int32_t direction, int32_t startMode) {
    if (direction != IMotor::DIRECTION_DOWN && direction != IMotor::DIRECTION_UP) {
        return illegalArgument("direction must be DIRECTION_DOWN or DIRECTION_UP");
    }
    if (startMode != IMotor::START_NORMAL && startMode != IMotor::START_FORCE) {
        return illegalArgument("startMode must be START_NORMAL or START_FORCE");
    }

    std::lock_guard<std::mutex> lock(mLock);
    if (auto status = initializeCalibrationLocked(); !status.isOk()) {
        return status;
    }

    int32_t moveState;
    if (!readNode(kMoveStatePath, &moveState)) {
        return errnoStatus(std::string("read ") + kMoveStatePath);
    }
    const int32_t moving = movingDirection(moveState);
    if (moving == direction && startMode == IMotor::START_NORMAL) {
        return ndk::ScopedAStatus::ok();
    }
    if (moving >= 0) {
        // The driver ignores direction changes while the motor is running.
        LOG(INFO) << "Stopping direction " << moving << " before moving " << direction;
        if (!writeNode(kEnablePath, "0") || !waitUntilStopped()) {
            return errnoStatus("stop motor");
        }
    }

    if (!writeNode(kDirectionPath, std::to_string(direction))) {
        return errnoStatus(std::string("write ") + kDirectionPath);
    }
    if (!writeNode(kEnablePath, "1")) {
        return errnoStatus(std::string("write ") + kEnablePath);
    }
    LOG(INFO) << "Motor move: direction=" << direction << " startMode=" << startMode;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Motor::getPosition(int32_t* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!readNode(kPositionPath, _aidl_return)) {
        return errnoStatus(std::string("read ") + kPositionPath);
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Motor::getMoveState(int32_t* _aidl_return) {
    std::lock_guard<std::mutex> lock(mLock);
    if (!readNode(kMoveStatePath, _aidl_return)) {
        return errnoStatus(std::string("read ") + kMoveStatePath);
    }
    return ndk::ScopedAStatus::ok();
}

}  // namespace aidl::vendor::oplus::hardware::motor
