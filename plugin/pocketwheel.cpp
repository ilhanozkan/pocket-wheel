#include "control_state.hpp"
#include "scssdk_input.h"
#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace {
using Clock = std::chrono::steady_clock;
double now_ms() { return std::chrono::duration<double, std::milli>(Clock::now().time_since_epoch()).count(); }
std::atomic<bool> running{false};
std::thread receiver;
int socket_fd = -1;
std::mutex state_mutex;
pocketwheel::ControlState state;
pocketwheel::ControlState cached_state;
pocketwheel::Output frame_output;
unsigned next_input = 0;
scs_log_t game_log = nullptr;

void receive_loop() {
    while (running.load()) {
        char buffer[513];
        sockaddr_in source{};
        socklen_t length = sizeof(source);
        const auto count = recvfrom(socket_fd, buffer, sizeof(buffer), 0,
                                    reinterpret_cast<sockaddr *>(&source), &length);
        if (count <= 0 || count > 512 || source.sin_addr.s_addr != htonl(INADDR_LOOPBACK)) continue;
        auto packet = pocketwheel::parse(std::string_view(buffer, static_cast<size_t>(count)));
        if (!packet) continue;
        std::lock_guard<std::mutex> lock(state_mutex);
        state.accept(*packet, now_ms());
    }
}
void stop() {
    running.store(false);
    if (receiver.joinable()) receiver.join(); // recv timeout bounds shutdown to 20 ms.
    if (socket_fd >= 0) close(socket_fd);
    socket_fd = -1;
    state = {};
    cached_state = {};
    frame_output = {};
}
SCSAPI_RESULT read_input(scs_input_event_t *const event, scs_u32_t flags, scs_context_t) {
    if (flags & SCS_INPUT_EVENT_CALLBACK_FLAG_first_in_frame) {
        // Never wait for the network receiver on the game's main thread.
        std::unique_lock<std::mutex> lock(state_mutex, std::try_to_lock);
        if (lock.owns_lock()) cached_state = state;
        frame_output = cached_state.sample(now_ms());
        next_input = 0;
    }
    if (next_input >= 9) return SCS_RESULT_not_found;
    std::memset(event, 0, sizeof(*event));
    event->input_index = next_input;
    if (next_input == 0) event->value_float.value = frame_output.steering;
    // ETS2's Normal pedal mode maps [-1, +1] to [released, fully pressed].
    // Internal/wire values remain [0, 1]; convert only at the game boundary so
    // startup, disarm and watchdog release all report -1 to the game.
    else if (next_input == 1) event->value_float.value = 2.0f * frame_output.throttle - 1.0f;
    else if (next_input == 2) event->value_float.value = 2.0f * frame_output.brake - 1.0f;
    else event->value_bool.value = (frame_output.buttons >> (next_input - 3)) & 1;
    ++next_input;
    return SCS_RESULT_ok;
}
}

extern "C" __attribute__((visibility("default")))
SCSAPI_RESULT scs_input_init(scs_u32_t version, const scs_input_init_params_t *const params) {
    if (version != SCS_INPUT_VERSION_1_00) return SCS_RESULT_unsupported;
    if (!params) return SCS_RESULT_invalid_parameter;
    stop();
    const auto *p = static_cast<const scs_input_init_params_v100_t *>(params);
    game_log = p->common.log;
    socket_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_fd < 0) return SCS_RESULT_generic_error;
    timeval timeout{0, 20000};
    if (setsockopt(socket_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0) {
        if (game_log) game_log(SCS_LOG_TYPE_error, "[Pocket Wheel] Could not set receive timeout; input device disabled.");
        stop();
        return SCS_RESULT_generic_error;
    }
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(26761);
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(socket_fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0) {
        if (game_log) game_log(SCS_LOG_TYPE_error, "[Pocket Wheel] Cannot bind localhost UDP 26761; close another game or test instance.");
        stop();
        return SCS_RESULT_generic_error;
    }
    static const scs_input_device_input_t inputs[] = {
        {"steering", "Steering", SCS_VALUE_TYPE_float, 0},
        {"throttle", "Gas", SCS_VALUE_TYPE_float, 0},
        {"brake", "Brake", SCS_VALUE_TYPE_float, 0},
        {"drive", "Drive", SCS_VALUE_TYPE_bool, 0},
        {"neutral", "Neutral", SCS_VALUE_TYPE_bool, 0},
        {"reverse", "Reverse", SCS_VALUE_TYPE_bool, 0},
        {"shiftup", "Shift Up", SCS_VALUE_TYPE_bool, 0},
        {"shiftdown", "Shift Down", SCS_VALUE_TYPE_bool, 0},
        {"parkingbrake", "Parking Brake", SCS_VALUE_TYPE_bool, 0},
    };
    scs_input_device_t device{};
    device.name = "pocketwheel";
    device.display_name = "Pocket Wheel";
    device.type = SCS_INPUT_DEVICE_TYPE_generic;
    device.input_count = 9;
    device.inputs = inputs;
    device.input_event_callback = read_input;
    if (p->register_device(&device) != SCS_RESULT_ok) { stop(); return SCS_RESULT_generic_error; }
    try {
        running.store(true);
        receiver = std::thread(receive_loop);
    } catch (...) { stop(); return SCS_RESULT_generic_error; }
    if (game_log) game_log(SCS_LOG_TYPE_message, "[Pocket Wheel] Input device registered: 3 analog axes, 6 buttons. Listening on 127.0.0.1:26761.");
    return SCS_RESULT_ok;
}

extern "C" __attribute__((visibility("default")))
SCSAPI_VOID scs_input_shutdown() {
    stop();
    if (game_log) game_log(SCS_LOG_TYPE_message, "[Pocket Wheel] Input device stopped.");
    game_log = nullptr;
}
