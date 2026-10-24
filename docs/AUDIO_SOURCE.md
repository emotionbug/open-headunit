# Audio processing: source, rebuilding and distribution

## Obtain the matching source

Repository: https://github.com/andreknieriem/open-headunit
Builds: https://github.com/andreknieriem/open-headunit/actions
Releases: https://github.com/andreknieriem/open-headunit/releases

The About screen shows the APK's build revision. For CI APKs, download the
accompanying `open-headunit-source.tar.gz` and `SOURCE_REVISION.txt` from the
same build artifact as the APK. This archive contains the checked-out tree
actually built, including PR merge revisions, native libraries, interface
definitions and build scripts. CI refuses a modified checkout rather than
mislabeling an archive of HEAD as matching source. GitHub Actions artifact
downloads may require signing in and are subject to artifact expiry.

For a published release, use the corresponding source supplied alongside
that APK. With a Git checkout, use `git checkout <revision>` for that exact
revision. A revision ending in `-dirty` has local changes not identified by
the commit alone: obtain those changes from the APK distributor as well.
Do not substitute a newer source tree just because its version name matches.

## Build, modify and relink

Use JDK 21 and an Android SDK with platform 36. Android NDK 29.0.14206865
and Android CMake 3.22.1 are pinned in `app/build.gradle.kts`. Gradle and the
remaining dependencies are declared in the checked-in build files and wrapper.
Network access is needed to download those build dependencies on the first run.

From the source root (or the `open-headunit` directory after extracting):

    export ANDROID_HOME=/path/to/android-sdk
    sdkmanager "platforms;android-36" "ndk;29.0.14206865" "cmake;3.22.1"
    ./gradlew :app:assembleGithubDebug :app:testGithubDebugUnitTest

The APK is written to `app/build/outputs/apk/github/debug/`. An extracted
archive has no Git metadata, so its build-revision label may be `unknown`;
`SOURCE_REVISION.txt` identifies the original archive. This is a source-rebuild
procedure, not a promise of byte-identical APKs or matching signing certificates.

The independent native time-correction code and JNI entry points live under
`app/src/main/cpp/`, starting at `audio_time_stretcher.cpp` and
`audio_time_stretcher_jni.cpp`. Source selection is in that directory's
`CMakeLists.txt`; the implementations are compiled into `libhur_time_stretch.so`.
Change the source and rebuild the APK with the command above to relink it.
No external time-stretch library or opaque prebuilt time-stretch binary is required.

For native regression tests, use a host C++17 compiler, JDK and CMake 3.24+.
See `app/src/test/native-time-stretch/README.md` and run
`bash app/src/test/run-audio-host.sh` after Gradle has populated its Kotlin cache.
Host tests use test doubles for Android APIs and do not replace device testing.

Install a debug build with Android's package installer or `adb install -r`.
It is signed with your local debug key, not the maintainer's release key.
If the installed app has a different signature, back up its settings/data
before uninstalling it to install your version; uninstalling removes app data.
A distributor of a device that restricts installation must also supply any
installation information required by the applicable licenses.

## Distributor checklist

- Keep the project AGPL license and source/build guide in the APK; both are
  available offline in About, alongside the build revision. Verify packaging
  with `python3 app/src/test/packaging/check_source_assets.py <apk>`.
- Supply the complete corresponding source for the exact distributed build,
  including any further modifications and build/interface files. The source
  archive is created before CI builds and accompanies the APK in both APK
  workflows. Do not distribute only the APK while dropping its source access.
- When publishing or mirroring a release, publish/preserve the matching source
  archive and revision beside the APK. An expiring Actions artifact or a link
  to the latest upstream branch is not a lasting release source distribution.
- Preserve the project license and copyright notices, document modifications,
  and meet the license requirements of the other included dependencies.

## Independent methods and research

The five choices are OHU (default), WSOLA, Phase Vocoder, Phase Vocoder
Short, and Phase Gradient. There is no automatic engine selection. Saved values for removed
engines reset to OHU rather than silently selecting a different algorithm.
Native methods apply only to PCM music on Android 5.0 or newer. Navigation,
voice responses, system sounds, AAC, unsupported devices and native-load
failure retain the OHU path. Reconnect after changing the setting.

The listening comparison exercises native DSP tempos from 0.5× to 2.0×.
This comparison range does not change the app's automatic catch-up policy or
add a playback-rate setting. A broader in-app correction range remains a
separate decision after listening.

WSOLA aligns overlapping waveform segments by similarity. A phase vocoder
works on short-time spectra and advances phase to change duration without
intentionally shifting pitch; the Short variant uses a shorter analysis
window. Phase Gradient reconstructs phases by integrating phase gradients.
These are independent paper-based implementations, not renamed
third-party libraries or claims of equivalence to their proprietary or
open-source engines. The algorithms involve different waveform-alignment,
frequency-resolution and transient-smearing tradeoffs. Their names do not
establish an audible-quality or CPU ranking.

Research references:

- Driedger and Müller, [A Review of Time-Scale Modification of Music Signals](https://www.audiolabs-erlangen.de/content/05_fau/professor/00_mueller/03_publications/2016_DriedgerMueller_TSMOverview_AppliedSciences_ePrint.pdf)
  (2016), sections 4–5: WSOLA, overlap-add and phase-vocoder methods.
- Roelands and Verhelst, [Waveform similarity based overlap-add (WSOLA) for time-scale modification of speech: structures and evaluation](https://www.isca-archive.org/eurospeech_1993/roelands93_eurospeech.html)
  (1993): waveform-similarity alignment.
- Laroche and Dolson, [Improved Phase Vocoder Time-Scale Modification of Audio](https://seniordesign.cs.tcu.edu/1516Ribbit/resources/ImprovedPhaseVocoder.pdf)
  (1999), section III.C: identity phase locking.
- The [audio research comparison](AUDIO_RESEARCH.md) documents Phase Gradient
  and the modern-method selection rationale, with their limits and references.

Published results do not measure these particular implementations. This source
guide reports no measured listening-quality ranking or Android CPU result.
Use identified inputs, rendered WAVs, timing measurements and device listening
comparisons before drawing those conclusions; passing regression tests alone
does not establish perceptual equivalence.

This guide covers audio processing and the project source-distribution path.
It is not a substitute for reviewing the licenses and distribution requirements
of all dependencies.
