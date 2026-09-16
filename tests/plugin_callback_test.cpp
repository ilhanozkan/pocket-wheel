// Exercise the actual SDK callback without initializing its socket or touching a game.
// This checks device events, not the closed-source game's binding-capture behavior.
#include "../plugin/pocketwheel.cpp"
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>

namespace {
std::array<float, 9> frame(bool activation = false) {
    std::array<float, 9> values{};
    for (unsigned index = 0; index != values.size(); ++index) {
        scs_input_event_t event;
        std::memset(&event, 0xff, sizeof(event));
        unsigned flags = index == 0 ? SCS_INPUT_EVENT_CALLBACK_FLAG_first_in_frame : 0;
        if (activation && index == 0) flags |= SCS_INPUT_EVENT_CALLBACK_FLAG_first_after_activation;
        assert(read_input(&event, flags, nullptr) == SCS_RESULT_ok);
        assert(event.input_index == index);
        values[index] = index < 3 ? event.value_float.value : event.value_bool.value;
    }
    scs_input_event_t end{};
    assert(read_input(&end, 0, nullptr) == SCS_RESULT_not_found);
    assert(read_input(&end, 0, nullptr) == SCS_RESULT_not_found);
    return values;
}

void accept(unsigned sequence, unsigned buttons, bool armed = true) {
    pocketwheel::Frame packet{"0123456789abcdef", static_cast<int32_t>(sequence),
                             2500, 7000, 2000, static_cast<int>(buttons), armed};
    assert(state.accept(packet, now_ms()));
}

void expect(const std::array<float, 9> &values, unsigned buttons) {
    assert(std::abs(values[0] - .25f) < 1e-6f);
    assert(std::abs(values[1] - .4f) < 1e-6f);
    assert(std::abs(values[2] - (-.6f)) < 1e-6f);
    for (unsigned bit = 0; bit < 6; ++bit) assert(values[bit + 3] == ((buttons >> bit) & 1));
}
}

int main() {
    assert(sizeof(scs_input_event_t) == 28);
    assert(sizeof(scs_input_device_input_t) == 24);
    assert(sizeof(scs_input_device_t) == 56);
    assert(frame(true) == (std::array<float, 9>{0, -1, -1, 0, 0, 0, 0, 0, 0}));
    accept(1, 0, false);
    unsigned sequence = 2;
    for (unsigned buttons : {1u, 2u, 0u, 4u, 0u, 8u, 16u, 32u, 63u, 0u}) {
        accept(sequence++, buttons);
        // A held D/R must remain high in every SDK frame, including reactivation.
        for (unsigned count = 0; count < 120; ++count) expect(frame(count == 60), buttons);
    }
    accept(sequence++, 1);
    expect(frame(), 1);
    accept(sequence++, 4);
    std::atomic<bool> receiver_locked{false}, release_receiver{false};
    std::thread receiver_busy([&] {
        std::lock_guard<std::mutex> lock(state_mutex);
        receiver_locked.store(true);
        while (!release_receiver.load()) std::this_thread::yield();
    });
    while (!receiver_locked.load()) std::this_thread::yield();
    expect(frame(), 1); // Main-thread callback must never block on another thread.
    release_receiver.store(true);
    receiver_busy.join();
    expect(frame(), 4);
    accept(sequence++, 0, false);
    const auto released = frame();
    assert(released[1] == -1 && released[2] == -1);
    for (unsigned index = 3; index < released.size(); ++index) assert(released[index] == 0);
    // Test the game's actual Normal-mode formula at rest, midway and full travel.
    // A restored default deadzone must not turn a released pedal into input.
    for (int pedal : {0, 5000, 10000}) {
        pocketwheel::Frame packet{"0123456789abcdef", static_cast<int32_t>(sequence++), 0, pedal, pedal, 0, true};
        assert(state.accept(packet, now_ms()));
        const auto values = frame();
        for (unsigned axis : {1u, 2u}) {
            assert(std::abs(values[axis] - (pedal / 5000.0f - 1.0f)) < 1e-6f);
            assert(std::abs((values[axis] * .5f + .5f) - pedal / 10000.0f) < 1e-6f);
            const float deadzone = .1f;
            const float normalized = std::clamp((values[axis] * .5f + .5f - deadzone) / (1 - deadzone), 0.0f, 1.0f);
            if (pedal == 0) assert(normalized == 0);
            if (pedal == 10000) assert(normalized == 1);
        }
    }
    // The plugin's independent watchdog must return the same released-axis value.
    pocketwheel::Frame stale{"0123456789abcdef", static_cast<int32_t>(sequence++), 0, 10000, 10000, 63, true};
    assert(state.accept(stale, now_ms() - 400));
    const auto timed_out = frame();
    assert(timed_out[1] == -1 && timed_out[2] == -1);
    for (unsigned index = 3; index < timed_out.size(); ++index) assert(timed_out[index] == 0);
    assert(socket_fd == -1 && !receiver.joinable());
    std::cout << "PASS: SDK ABI; all six button bits; held D/R across 120 frames; activation; release; contention. No socket opened.\n";
}
