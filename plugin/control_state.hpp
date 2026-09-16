#pragma once
#include <algorithm>
#include <array>
#include <charconv>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace pocketwheel {
struct Frame {
    std::string session;
    int32_t sequence = 0;
    int steering = 0, throttle = 0, brake = 0, buttons = 0;
    bool armed = false;
};
struct Output {
    float steering = 0, throttle = 0, brake = 0;
    unsigned buttons = 0;
    bool armed = false;
};
inline std::optional<Frame> parse(std::string_view text) {
    if (text.empty() || text.size() > 512) return {};
    if (text.back() == '\n') text.remove_suffix(1);
    std::array<std::string_view, 8> parts;
    for (size_t i = 0; i < parts.size(); ++i) {
        const auto separator = text.find('|');
        if (i == parts.size() - 1) {
            if (separator != std::string_view::npos) return {};
            parts[i] = text;
        } else {
            if (separator == std::string_view::npos) return {};
            parts[i] = text.substr(0, separator);
            text.remove_prefix(separator + 1);
        }
    }
    if (parts[0] != "PWL1" || parts[1].size() != 16) return {};
    for (const char c : parts[1]) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return {};
    std::array<int, 6> v{};
    for (size_t i = 2; i < parts.size(); ++i) {
        auto p = parts[i];
        if (p.empty()) return {};
        const auto result = std::from_chars(p.data(), p.data() + p.size(), v[i - 2]);
        if (result.ec != std::errc{} || result.ptr != p.data() + p.size()) return {};
    }
    if (v[0] < 0 || v[1] < -10000 || v[1] > 10000 || v[2] < 0 || v[2] > 10000 ||
        v[3] < 0 || v[3] > 10000 || v[4] < 0 || v[4] > 63 || v[5] < 0 || v[5] > 1) return {};
    return Frame{std::string(parts[1]), v[0], v[1], v[2], v[3], v[4], v[5] == 1};
}

// A clock supplied by callers keeps watchdog tests deterministic.
class ControlState {
    std::string session_;
    int32_t sequence_ = -1;
    double last_received_ = -1;
    bool needs_disarm_ = true;
    Output output_;
public:
    bool accept(const Frame &frame, double now_ms) {
        if (frame.session != session_) {
            if (frame.armed) return false;
            session_ = frame.session;
            sequence_ = -1;
            output_ = {};
            needs_disarm_ = true;
        }
        if (frame.sequence <= sequence_) return false;
        if (last_received_ >= 0 && now_ms - last_received_ > 300) needs_disarm_ = true;
        sequence_ = frame.sequence;
        last_received_ = now_ms;
        if (!frame.armed) {
            needs_disarm_ = false;
            output_ = {};
            // The trusted bridge can carry its watchdog's steering ramp while disarmed.
            output_.steering = frame.steering / 10000.0f;
        } else if (!needs_disarm_) {
            output_ = {frame.steering / 10000.0f, frame.throttle / 10000.0f,
                       frame.brake / 10000.0f, static_cast<unsigned>(frame.buttons), true};
        } else output_ = {};
        return true;
    }
    Output sample(double now_ms) {
        auto result = output_;
        const double age = now_ms - last_received_;
        if (last_received_ < 0 || age > 300) {
            needs_disarm_ = true;
            result.throttle = result.brake = 0;
            result.buttons = 0;
            result.armed = false;
            result.steering *= static_cast<float>(std::clamp(1.0 - (age - 300) / 200.0, 0.0, 1.0));
        }
        return result;
    }
};
} // namespace pocketwheel
