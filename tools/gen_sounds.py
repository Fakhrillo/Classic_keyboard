#!/usr/bin/env python3
"""Synthesises the typewriter sound effects into app/src/main/res/raw."""
import math, os, random, struct, wave

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
random.seed(7)


def write(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s / peak * 30000)) for s in samples))


def lowpass(x, a):
    y, out = 0.0, []
    for s in x:
        y += a * (s - y)
        out.append(y)
    return out


def strike(dur, partials, noise_tau, noise_gain, lp):
    n = int(SR * dur)
    noise = lowpass([random.uniform(-1, 1) for _ in range(n)], lp)
    out = []
    for i in range(n):
        t = i / SR
        s = noise[i] * noise_gain * math.exp(-t / noise_tau)
        for f, g, tau in partials:
            s += g * math.sin(2 * math.pi * f * t) * math.exp(-t / tau)
        out.append(s)
    return out


# Type bar hitting the platen: sharp click, short metallic ring, small body thump.
write("key", strike(0.09, [(2600, 0.35, 0.008), (4300, 0.22, 0.006), (190, 0.55, 0.018)], 0.005, 1.0, 0.75))
# Space bar: duller, lower.
write("space", strike(0.12, [(130, 0.9, 0.03), (900, 0.2, 0.012)], 0.009, 0.8, 0.25))
# Carriage-return bell.
bell = strike(0.9, [(2093, 0.7, 0.22), (4186, 0.3, 0.12), (5580, 0.18, 0.08)], 0.004, 0.5, 0.6)
write("ding", bell)
