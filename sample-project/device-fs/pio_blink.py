import time
import rp2
from machine import Pin

# Define the blink program.  It has one GPIO to bind to on the set instruction, which is an output pin.
# Use lots of delays to make the blinking visible by eye.
@rp2.asm_pio(set_init=rp2.PIO.OUT_LOW)
def blink():
    wrap_target()
    set(pins, 1)   [31]
    nop()          [31]
    nop()          [31]
    nop()          [31]
    nop()          [31]
    set(pins, 0)   [31]
    nop()          [31]
    nop()          [31]
    nop()          [31]
    nop()          [31]
    wrap()

instructions_per_full_cycle = 32 * 10
desired_blink_frequency_hz = 10
clock_freq_hz = desired_blink_frequency_hz * instructions_per_full_cycle

print(f"clock_freq_hz=${clock_freq_hz}")

sm = rp2.StateMachine(0, blink, freq=clock_freq_hz, set_base=Pin(2))

sm.active(1)
time.sleep(8)
sm.active(0)
