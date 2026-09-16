#include "control_state.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
using namespace pocketwheel;
int main() {
    const std::string base = "PWL1|0123456789abcdef|";
    for (auto bad : {"", "garbage", "PWL1|x|0|0|0|0|0|0", "PWL1|0123456789abcdef|0|10001|0|0|0|0",
                     "PWL1|0123456789abcdef|0|0|-1|0|0|0", "PWL1|0123456789abcdef|0|0|0|0|64|0",
                     "PWL1|0123456789abcdef|2147483648|0|0|0|0|0", "PWL1|0123456789abcdef|0|nan|0|0|0|0",
                     "PWL1|0123456789abcdef|0|0|0|0|0|2", "PWL1|0123456789abcdef|0|0|0|0|0|0|extra"}) assert(!parse(bad));
    ControlState state;
    assert(!state.accept(*parse(base + "0|5000|9000|0|9|1"), 0)); // startup cannot be armed.
    assert(state.accept(*parse(base + "1|0|0|0|0|0"), 0));
    assert(state.accept(*parse(base + "2|5000|9000|2500|9|1"), 10));
    auto output = state.sample(20);
    assert(output.armed && output.steering == .5f && output.throttle == .9f && output.brake == .25f && output.buttons == 9);
    assert(!state.accept(*parse(base + "2|0|0|0|0|0"), 100)); // duplicate must not refresh.
    output = state.sample(410);
    assert(!output.armed && output.throttle == 0 && output.buttons == 0 && std::abs(output.steering - .25f) < .001f);
    assert(state.sample(511).steering == 0);
    assert(state.accept(*parse(base + "3|10000|10000|0|0|1"), 520));
    assert(!state.sample(530).armed); // timeout is latched.
    assert(state.accept(*parse(base + "4|0|0|0|0|0"), 540));
    assert(state.accept(*parse(base + "5|-10000|10000|0|63|1"), 550));
    assert(state.sample(555).armed && state.sample(555).steering == -1);
    assert(!state.accept(*parse("PWL1|ffffffffffffffff|0|0|0|0|0|1"), 560));
    assert(state.accept(*parse("PWL1|ffffffffffffffff|0|0|0|0|0|0"), 560));
    assert(!state.sample(561).armed);
    assert(state.accept(*parse("PWL1|ffffffffffffffff|1|2500|9000|9000|63|0"), 570));
    output = state.sample(571);
    assert(!output.armed && output.steering == .25f && output.throttle == 0 && output.brake == 0 && output.buttons == 0);
    assert(parse(base + "6|0|0|0|0|0\n"));
    std::cout << "Plugin packet validation, analog axes, watchdog, replay, session and rearm tests passed.\n";
}
