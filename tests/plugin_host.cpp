// A real SDK ABI host, used to exercise the compiled Intel plugin under Rosetta.
#include "scssdk_input.h"
#include <array>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <iostream>
#include <thread>
scs_input_event_callback_t callback = nullptr;
scs_context_t context = nullptr;
SCSAPI_VOID log_message(scs_log_type_t, scs_string_t text) { std::cerr << text << '\n'; }
SCSAPI_RESULT register_device(const scs_input_device_t *const device) {
    if (std::strcmp(device->name, "pocketwheel") || device->input_count != 9 || device->type != SCS_INPUT_DEVICE_TYPE_generic) return SCS_RESULT_invalid_parameter;
    for (unsigned i = 0; i < 9; ++i) if (device->inputs[i].value_type != (i < 3 ? SCS_VALUE_TYPE_float : SCS_VALUE_TYPE_bool)) return SCS_RESULT_invalid_parameter;
    callback = device->input_event_callback;
    context = device->callback_context;
    return SCS_RESULT_ok;
}
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    void *module = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!module) { std::cerr << dlerror() << '\n'; return 3; }
    const auto initialize = reinterpret_cast<decltype(&scs_input_init)>(dlsym(module, "scs_input_init"));
    const auto shutdown = reinterpret_cast<decltype(&scs_input_shutdown)>(dlsym(module, "scs_input_shutdown"));
    if (!initialize || !shutdown) return 4;
    scs_input_init_params_v100_t params{};
    params.common.game_name = "Pocket Wheel SDK Test Host";
    params.common.game_id = "eut2";
    params.common.game_version = SCS_MAKE_VERSION(1, 0);
    params.common.log = log_message;
    params.register_device = register_device;
    if (initialize(0, &params) != SCS_RESULT_unsupported || initialize(SCS_INPUT_VERSION_1_00, &params) != SCS_RESULT_ok) return 5;
    const int duration = argc > 2 ? std::atoi(argv[2]) : 5;
    std::cout << "READY" << std::endl;
    std::array<float, 9> previous{};
    const auto start = std::chrono::steady_clock::now();
    while (std::chrono::steady_clock::now() - start < std::chrono::seconds(duration)) {
        std::array<float, 9> values{};
        for (unsigned index = 0; index < 9; ++index) {
            scs_input_event_t event{};
            const auto flags = index == 0 ? SCS_INPUT_EVENT_CALLBACK_FLAG_first_in_frame : 0;
            if (callback(&event, flags, context) != SCS_RESULT_ok || event.input_index != index) return 6;
            values[index] = index < 3 ? event.value_float.value : event.value_bool.value;
            if (!std::isfinite(values[index]) || (index < 3 && (values[index] < -1 || values[index] > 1)) ||
                (index >= 3 && values[index] != 0 && values[index] != 1)) return 9;
        }
        scs_input_event_t last{};
        if (callback(&last, 0, context) != SCS_RESULT_not_found) return 7;
        if (values != previous) {
            std::cout << '[';
            for (unsigned i = 0; i < 9; ++i) std::cout << (i ? "," : "") << values[i];
            std::cout << ']' << std::endl;
            previous = values;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    shutdown();
    // A second full load cycle verifies the socket/thread cleanup contract.
    if (initialize(SCS_INPUT_VERSION_1_00, &params) != SCS_RESULT_ok) return 8;
    shutdown();
    dlclose(module);
}
