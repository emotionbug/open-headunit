# Independent native time-stretch regression tool

The APK and this host suite compile the same independently written C++17 DSP and
radix-2 FFT in `audio_time_stretcher.cpp`. No SoundTouch, Rubber Band, FFT library,
or code recovered from those libraries is used. The DSP API accepts OHU's 48 kHz
interleaved stereo PCM16 at tempos 0.5–2.0 (half speed through double speed).
This experimental capability is separate from the application's current
correction policy. These engines do not claim R3 quality or artifact-free output
throughout that range.

## Algorithms and research

All windows are centered, periodic Hann windows. Synthesis uses a fixed hop and
actual overlap-weight normalization (Hann for WSOLA, squared Hann for spectral
engines). Analysis positions follow the inverse integral of source-frame
`1 / tempo`, stored alongside input. A later `process()` call cannot relabel the
tempo of already accepted source, including lookahead and the Stop tail.

| Engine / native ID | Window | Synthesis hop | Additional lookahead / alignment |
| --- | --- | --- | --- |
| WSOLA / 1 | 1920 frames (40 ms) | 480 (10 ms) | ±240-frame (5 ms) nominal search |
| Phase Vocoder / 2 | 2048 (42.67 ms) | 512 (10.67 ms) | None beyond centered-window support |
| Phase Vocoder Short / 3 | 1024 (21.33 ms) | 256 (5.33 ms) | None beyond centered-window support |
| Phase Gradient / 4 | 2048 (42.67 ms) | 512 (10.67 ms) | One future STFT, 5.33–21.33 ms of source across the supported tempo range |

WSOLA follows Driedger and Müller's equations 6–11: compare a candidate against
the previous adjusted frame's natural progression, not against a fixed nominal
frame. If natural progression is inside the nominal bounds it is an exact match;
otherwise normalized joint-channel correlation selects an offset. Correlation
uses every fourth overlap sample, a four-sample coarse search and ±3-sample
refinement. Both channels share the offset; their energies are summed rather
than summing L+R, which would cancel antiphase sources. WSOLA does not implement
the full transient-detection/freezing scheme discussed in section 4.3. It can
still warble on polyphony and smear, double, or weaken transients; its short
search does not cover half a period of very low bass.

The two Phase Vocoder modes implement instantaneous frequency from unwrapped
phase advance (Driedger/Müller equations 20–27), and Laroche/Dolson identity phase
locking (section III.C.1, equations 12–14). Joint stereo spectral magnitudes select
local peaks, midpoint regions share the peak's complex rotation, and the stronger
channel supplies its phase advance. Applying the same rotation to both channels
preserves their within-bin relative phase, including antiphase and isolated
channels. Positive joint spectral flux exceeding 35% of the previous magnitude
sum resets rotation to the source phase; this is a simple onset heuristic, not
harmonic/percussive separation or a guarantee against transient smearing. The
short mode trades spectral resolution for lower lookahead.

Phase Gradient is a separate candidate based on Průša/Holighaus, *Phase Vocoder
Done Right*, published at EUSIPCO 2017 and reposted on arXiv in February 2022,
equations 13–18 and Algorithm 1.
It uses centered time/frequency phase differences, trapezoidal integration, and
an array-backed magnitude-priority max heap deciding whether to propagate in
time or frequency. It uses one future STFT and does not use peak picking or an
onset detector. FFT phases are converted from a left-edge to a centered-window
origin before taking frequency differences. Explicit OHU adaptations are:

- Actual rounded analysis hops account for the variable-tempo timeline;
  vertical integration uses the local synthesis/analysis ratio.
- Joint-channel magnitudes order the heap, the stronger local channel supplies
  each gradient, and one rotation is applied to both channels. This is a stereo
  adaptation, not a reproduction of the paper's mono evaluation.
- Bins below `1e-6` of the current/previous maximum retain their input phase,
  rather than using the paper's randomized low-energy phase.
- Initial phases and an unmodified unity timeline retain source phases. This
  avoids numerical integration changing PCM when no correction is requested.

This candidate is not labeled state of the art or automatically superior.
Heap integration and phase locking have different failure modes; listening and
measurement across the supported stereo workload are necessary. A 1% speedup is
one useful comparison point, not an algorithm-selection requirement. None of
these engines changes pitch by resampling, and none includes a neural model.

Primary sources (equations, not implementation source):

- Driedger and Müller, *A Review of Time-Scale Modification of Music Signals*
  (2016), sections 3–5, especially WSOLA equations 6–11 and PV equations 12–27:
  <https://www.audiolabs-erlangen.de/content/05_fau/professor/00_mueller/03_publications/2016_DriedgerMueller_TSMOverview_AppliedSciences_ePrint.pdf>
- Laroche and Dolson, *Improved Phase Vocoder Time-Scale Modification of Audio*
  (1999), section III.C.1:
  <https://seniordesign.cs.tcu.edu/1516Ribbit/resources/ImprovedPhaseVocoder.pdf>
- Průša and Holighaus, *Phase Vocoder Done Right*, EUSIPCO 2017:
  <https://www.eurasip.org/Proceedings/Eusipco/Eusipco2017/papers/1570343436.pdf>
  (readable 2022 arXiv repost: <https://arxiv.org/html/2202.07382v1>).

## Streaming bounds and lifecycle

All DSP storage is allocated at construction. `process()` and `receive()` use
fixed arrays with no heap allocation on valid calls. The input ring holds 16,384
frames; the normalized overlap ring holds 4,096 frames. The gradient heap holds
at most twice the 1,025 nonnegative-frequency bins. There is no growing output
queue: `receive()` synthesizes only as required by the consumer. Calling
`process()` without receiving eventually throws `overflow_error` **before**
accepting any of the new block; the caller may drain and retry that whole block.

Input blocks contain 1–480 frames, and all finite tempos outside 0.5–2.0 are
rejected. Non-finite tempos, null PCM, unknown engine IDs and input after finish
are rejected. Zero-capacity receive is a no-op. Receive respects even one-frame
capacity. Finalization is idempotent, including empty streams. Source is
zero-extended only for analysis support; final output contains exactly
`round(sum(sourceFrames / assignedTempo))` frames, rather than vendor-specific
synthesis padding. One-frame sources survive. Quantization rounds and saturates
to PCM16; unity reconstruction is exact within one integer rounding unit.

Finite-stream reconstruction includes one terminal identity-phase Hann grain
centered on the final source sample and placed at the final output sample. This
is a signal-independent boundary condition, not impulse detection or a sample
overwrite: it participates in ordinary normalized overlap-add and leaves the
duration unchanged. The scheduler merges this off-grid grain with the regular
synthesis frames before releasing affected output. For spectral engines its
weight is squared Hann, equivalent to analysis followed by identity-phase
inverse transformation and synthesis windowing. Without an endpoint-centered
grain, hop-grid phase propagation can place a final onset just outside the
rounded duration and cropping would remove it. Boundary blending can differ
from the steady-state stretch over the last half-window; unity remains unchanged.

At unity, theoretical first-output source requirements are 2160 frames for WSOLA,
2048 for Phase Vocoder, 1024 for Short and 2560 for Phase Gradient. With 480-frame
input blocks these round upward to 50, 50, 30 and 60 ms, respectively. At tempo
2.0, the corresponding bounds are 70, 70, 40 and 90 ms. These are
algorithmic/source-frame bounds, not measured Android wall-clock latency or CPU
results. The regression checks all modes return output within 100 ms of supplied
source at each of 0.5, 0.8, 1, 1.01, 1.05, 1.1, 1.25, 1.5 and 2.0. Analysis
hops span one-eighth to one-half of the spectral window. Fixed synthesis hops
keep overlap storage bounded regardless of output expansion; source-clock and
natural-progression history remain retained independently of receive capacity.

## Running the tests

Use CMake 3.24+, a host C++17 compiler and a JDK (set `JAVA_HOME` if discovery
fails), from the repository root:

```sh
cmake -S app/src/test/native-time-stretch -B build/native-time-stretch -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-time-stretch --parallel 4
ctest --test-dir build/native-time-stretch --output-on-failure
```

The native consumer tests check duration, unity PCM, one-frame sources, small
receive capacities with guards, bounded lookahead, pitch (a 440 Hz tone must not
become the 444.4 Hz result of resampling), mono/antiphase/channel isolation,
source-tempo assignment independent of input partitioning, changing-tempo late
source, transient audibility/timing, invalid arguments, empty/repeated Stop and
bounded-input backpressure with retry. These are behavioral checks, not replicas
of FFT or overlap implementation internals and not subjective quality tests.

Duration, short-source, pitch, stereo, lookahead and transient fixtures exercise
all nine tempos listed above. The variable-tempo stream jumps through those
ratios in a different order and compares 480-frame versus 17-frame input
partitioning, including its nonzero final source. Single-final-frame fixtures
cover several source lengths relative to hop boundaries. Failure output includes
engine, source length and tempo. This does not authorize an application-level
chunk-drop or cut-down catch-up path.

The JVM smoke loads production JNI entry points with `-Xcheck:jni`, checks
malformed/repeated direct-buffer binding, unbound calls, GC lifetime, final drain
and close. A test-only array bridge feeds the same DSP as a transfer reference:
720 fixtures compare all returned PCM and block lengths across all four engines
with both slow-to-fast and fast-to-slow source-block tempo changes.
The reference library never ships in the APK. The library scope check rejects
references to the removed vendors without requiring incidental private symbols.

Android CI also runs the Kotlin production-worker harness through
`bash app/src/test/run-audio-host.sh` after Gradle unit tests populate its cache.
To exercise the Kotlin mixer/worker with this host JNI library directly:

```sh
OHU_TEST_NATIVE_DSP=1 \
JAVA_TOOL_OPTIONS="-Djava.library.path=$PWD/build/native-time-stretch" \
python3 app/src/test/host-audio/run.py
```

This opt-in run fails if the native library cannot load rather than accepting an
OHU fallback. Android API calls still use test doubles, not a device HAL. Host
passes do not establish Android CPU cost, listening quality, or device behavior.
