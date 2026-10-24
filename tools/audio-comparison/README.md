# Local engine listening comparison

Runs actual production JNI DSP on caller-supplied WAVs and produces a local
HTML player, blinded-label excerpts, named full outputs, measurement JSON and
`comparison-audio.zip`. No recordings or previous third-party library binaries
are bundled in this repository or the APK.

Requirements: JDK 21, Python with NumPy, Kotlin compiler jars in the Gradle cache,
and a Release host build of the independent engines:

```sh
cmake -S app/src/test/native-time-stretch -B build/native-time-stretch -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-time-stretch --parallel 2
ctest --test-dir build/native-time-stretch --output-on-failure
```

Input names are supplied without `.wav`. Files must be nonempty stereo PCM16
WAVs at 48 kHz. Use identical input files for every stage, with enough duration
for the 1–7 second listening excerpts. No automatic resampling is performed.

## Existing-policy comparison

Provide an externally preserved previous `libhur_time_stretch.so`, with native
IDs 1/2/3 for SoundTouch/R3/R3 Short and the registered-direct-buffer JNI ABI.
The script also renders the production OHU overlap core and the four new engines.
The directory containing each `.so` must contain that library under its normal
name, because the JVM loads it with `System.loadLibrary`.

```sh
python tools/audio-comparison/render.py \
  --inputs /path/to/inputs --output /path/to/comparison \
  --baseline-lib /path/to/preserved/libhur_time_stretch.so \
  --names ebu_castanets ebu_piano_music ebu_soprano ebu_speech ebu_pop
```

Profiles:

- `unity`: tempo 1.0, with no catch-up.
- `constant`: native tempo 1.01; OHU uses its production splice and pacing.
- `toggle`: native 1.0/1.01 alternating every 100 ms of source; OHU's own
  overlap/pacing can achieve a smaller total correction.
- `matched`: request each native engine at the average rate actually achieved
  by the corresponding OHU `constant` run, capped at the old adapter's 1.01
  limit. **This matches requested average rate, not guaranteed output length**:
  old R3 synthesis tails remain present and are reported, not silently clipped.

`--stage baseline` and `--stage independent` can be run successively in the
same output directory. The independent `matched` stage requires the existing
OHU `constant` row for every selected input. Each row records its driver and
library hashes. Subsequent stages replace only matching input/engine/profile
rows. Do not run writers concurrently into the same output directory.

## Wider-rate experiment

The previous production adapter rejects tempos outside 1.0–1.01 even though
its underlying libraries support a larger range. For a fair wider experiment,
keep that original library unchanged and build a separate research copy from
its preserved source revision. In that copy only, change the adapter's
`AudioTimeStretcher::process` range check to 0.5–2.0. Do not change library code,
engine options, padding, tempo handling or output collection. Record this
validation-only modification, exact source revision, original source archive
hash and resulting binary hash with the local results. This is **not** an APK
that was previously shipped.

Then use that separately built reference library:

```sh
python tools/audio-comparison/render.py \
  --inputs /path/to/inputs --output /path/to/comparison \
  --baseline-lib /path/to/widened-reference/libhur_time_stretch.so \
  --names ebu_castanets ebu_piano_music ebu_soprano ebu_speech ebu_pop \
  --profiles rate-0.5 rate-0.8 rate-1.05 rate-1.1 rate-1.25 rate-1.5 rate-2 wide-toggle
```

Tempo 0.5 means twice the duration; tempo 2 means half the duration. `wide-toggle`
alternates 0.5/2.0 every 100 ms of source: a deliberate stress case, not a proposed
catch-up policy. The existing OHU splice does not implement this range and is
omitted, rather than falsely labelled as processing it. The application policy
is intentionally unchanged pending listening.

## Listen and interpret

Open `index.html` locally, or serve its directory:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory /path/to/comparison
```

Listen before expanding “엔진 이름 보기”. Labels are shuffled independently for
each input/profile with a reproducible seed. This hides engine names but is not
a formal ABX test. Original reference and processed files can have different
speed/duration, particularly in the existing-policy comparison. Full outputs
include their boundaries; use them to inspect startup and tail artifacts that
the 1–7 second excerpt deliberately excludes.

- All inputs receive the same 0.5 gain (−6.02 dB) for processing headroom. There
  is no per-output normalization, limiter or loudness matching that could hide
  a gain or clipping defect.
- Use castanets/impulses for softened or doubled attacks; piano for warbling;
  speech/soprano for hollow, echoing or metallic voices; low-level material for
  disappearing detail; and isolated/antiphase stereo for image corruption.
- Measurements use one same-signal warm-up and three measured runs. The output
  must be deterministic. Thread CPU includes JNI block transfer and collection,
  but excludes file I/O, allocation of recording arrays and engine setup.
- Native block timing includes each process-plus-drain and the final drain;
  report p99 and worst maximum separately. It is host wall time, not callback
  blocking on Android. First-output source quantity is not hardware latency.
- The OHU row isolates waveform selection and overlap with production pacing,
  not the full mixer/worker/decoder/device pipeline. Its cost is not an
  equivalent whole-pipeline CPU baseline against the JNI rows.
- Unity error, duration, clipping, pitch and stereo metrics test specific
  properties, not overall audible quality. No automatic quality ranking is
  generated. Use actual listening and then device testing.

See [research and external feedback](../../docs/AUDIO_RESEARCH.md) for sources,
limitations and why newer publication dates do not establish a better default.
Keep third-party test recordings local unless their redistribution terms have
been checked for the intended distribution. The old Codex EBU SQAM inputs are
used for local research comparison, not committed or included in release APKs.
