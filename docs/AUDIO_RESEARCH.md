# Independent time-correction research and comparison

## Decision and scope

SoundTouch and Rubber Band are removed from the application source/build, not
renamed or copied into a new engine. The independently written candidates are
WSOLA, an identity-phase-locked phase vocoder with two window lengths, and
phase-gradient heap integration. The FFT is also independently implemented.
The original OHU splice remains the default. These are candidates to compare,
not claims of equivalence to R3 or superiority to mature libraries.

The research range is **0.5–2.0 tempo**: values above 1 shorten duration and
values below 1 lengthen it. A paper's duration multiplier is often the inverse
of this tempo, so a paper's “2× stretch” means our tempo 0.5, not 2.0.
The 1% ceiling of the existing automatic catch-up policy is **not** a criterion
for excluding algorithms from research. By user decision, automatic application
policy remains unchanged until the wider-rate listening comparison is reviewed.
No playback-rate slider or automatic engine selection is added.

Do not confuse time compression with dropping a queued packet or whole passage.
Pitch-preserving TSM changes the waveform/sample count; it cannot preserve every
original sample while producing a shorter signal. Duration/pitch tests do not
prove that every onset, syllable or subtle musical detail remains perceptually
unchanged. The listening comparison retains beginning/end output and includes
transient, low-level and stereo material specifically to reveal such losses.

## Primary literature and applicability

This is a focused search through published papers and available preprints,
including 2024–2026 work; it is not a claim of exhaustive literature coverage.
Implementation sources from other DSP projects are not used as templates.
Published listening results below belong to those papers' implementations,
inputs and participants, not to these OHU implementations.

| Work | Method and evidence | Decision for OHU |
| --- | --- | --- |
| [Roelands/Verhelst, WSOLA structures and evaluation (1993)](https://www.isca-archive.org/eurospeech_1993/roelands93_eurospeech.html) and [Driedger/Müller review (2016)](https://www.audiolabs-erlangen.de/content/05_fau/professor/00_mueller/03_publications/2016_DriedgerMueller_TSMOverview_AppliedSciences_ePrint.pdf), §§3–5 | Relocate overlapping waveform windows and search around nominal analysis positions for similarity to the previous window's natural continuation. The review identifies polyphonic warbling and transient duplication/skipping, and explains transient freezing. | Implement a stereo-linked WSOLA baseline. Document its limited search, approximate correlation and endpoint adaptation rather than presenting it as a complete transient-preserving method. Test bass, quiet secondary tones and attacks, not just speech. |
| [Laroche/Dolson, Improved Phase Vocoder (1999)](https://seniordesign.cs.tcu.edu/1516Ribbit/resources/ImprovedPhaseVocoder.pdf), §III.C.1 | Identity phase locking rotates the bins around a spectral peak together, preserving local vertical phase relationships. The paper explicitly says consistency metrics do not reliably rank audible phasiness; phase-locked PV is not universally better than time-domain methods for speech. | Implement an original FFT and identity-phase-locking baseline, with long/short windows, linked stereo rotations and an explicitly heuristic onset reset. No “quality priority” label. |
| [Průša/Holighaus, Phase Vocoder Done Right, EUSIPCO 2017](https://www.eurasip.org/Proceedings/Eusipco/Eusipco2017/papers/1570343436.pdf); [2022 arXiv repost with readable equations](https://arxiv.org/html/2202.07382v1) | Equations 13–18 estimate time/frequency phase gradients; Algorithm 1 uses magnitude-priority heap integration. Centered time differences require one future STFT frame. Listening used duration factors 1.5 and 2; eight participants were recruited and two excluded. The authors report remaining weaknesses for voiced monophonic speech. | Implement a separate Phase Gradient candidate, not a silent replacement for phase locking. The stereo linkage, deterministic low-energy bins and streaming boundaries are our adaptations. Compare directly; the 2022 upload is **not** a new 2022 invention. |
| [TSM-Net, Audio Time-Scale Modification with Temporal Compressing Networks (2023)](https://arxiv.org/html/2210.17152v3) | Learned encoder/decoder and interpolation of a compressed Neuralgram. Evaluation uses 22.05 kHz audio. Average MOS: WSOLA 3.08, PV 3.07, proposed model 3.06. Reported speed benefit leverages a GPU; training is reported as one week on a Tesla P100 per dataset. | Interesting alternative, but no demonstrated 48 kHz stereo, causal low-end Android CPU advantage. Independently training a model is a separate prerequisite; borrowing its trained weights would not meet the intended dependency removal by itself. Do not equate faster GPU inference with lower head-unit CPU cost. |
| [Moliner et al., Noise Morphing (2024 publication; 2023 preprint)](https://arxiv.org/pdf/2312.14586) | Separate sines/transients/noise, then shape white-noise excitation with interpolated residual spectra. Thirteen listeners, five mono excerpts, duration factors 2, 4 and 8. Strong results for extreme expansion; stereo/multichannel extension is explicitly future work. | Keep as a researched alternative, not discard solely for using large ratios. The blockers to immediate adoption are stereo coherence, separation latency/cost and reconstructing rather than retaining residual waveform structure. A mono listening result does not establish stereo compatibility. |
| [Polak/Erkut, Low-Latency Pitch-Shifting with STN Decomposition (2025)](https://pub.dega-akustik.de/DAS-DAGA_2025/files/upload/paper/635.pdf) | Online sines/transients/noise separation with past-only median filters, noise morphing and a spectral pitch shifter. Highest-setting latency is 7,325 samples at 44.1 kHz, about 166.1 ms. Eight listeners; authors report lower quality than commercial comparators and even the library used internally, with normalization/separation limitations. | Valuable evidence about adapting offline separation to live streams, but “low latency” is relative to studio pitch-shift plug-ins. Not evidence of an unqualified quality gain for an interactive receiver. Pitch shifting is related to, but not the same evaluation as, pure TSM. |
| [STSM-FiLM (2025)](https://arxiv.org/html/2510.02672v1) | Speech model conditioned on rate, trained against WSOLA-generated targets. All evaluation audio is 16 kHz. Mean listening scores: WavLM variant 4.40 and WSOLA 4.33, with overlapping reported confidence intervals. Tested speech, not general stereo music. | Do not claim neural superiority from a small numerical difference or apply speech metrics to music. No demonstrated CPU/streaming/stereo fit here; keep findings in the research comparison, not in the app. |
| [DiffATSM (2026 journal listing)](https://www.sciencedirect.com/science/article/pii/S0885230825001202), [author project/abstract](https://github.com/shjang-97/DiffATSM) | Speech-oriented adaptive generator using phonetic posteriorgrams plus diffusion post-processing. The author repository inspected contains only a README, with full implementation promised later. Publisher full text was inaccessible in this investigation. | Abstract-level evidence only. Insufficient implementation/evaluation detail to reproduce or claim live 48 kHz stereo performance; do not invent latency or quality numbers. |
| [Variable-Rate Harmonic-Percussive TSM with Real-Time Playback (2026 preprint)](https://arxiv.org/abs/2609.18999v1) | Whole recording is known beforehand. Separation is offline; table lookups replace analysis FFT/frequency work during variable-rate playback. Abstract reports 24 listeners, 1,114 pair ratings and roughly halved runtime for sufficiently dense precomputation. | The distinction is **known recording vs live incoming PCM**, not a 1% limit. Offline analysis of future phone audio is unavailable. Do not mistake real-time playback of a preanalysed recording for a causal live-input algorithm. |
| [Nielsen, Keyframe Time Stretching via Extrema Sampling, DAFx 2026](https://dafx.de/paper-archive/2026/papers/DAFx26_paper_21.pdf) | Sparse extrema representation, cubic reconstruction and content-adaptive OLA. Low-power target and operation-count estimates are relevant. But unity-rate reconstruction is already lossy: reported mean spectral-contrast loss 3.08 dB and odd-harmonic THD −38.1 dB. Fifteen-listener comparison rates WSOLA highest on four of five clips. Authors explicitly target a creative effect, not transparent restoration. | Do not discard for large ratios; reject as the default fidelity-preserving replacement because its own stated tradeoff changes timbre even without correction. Its adaptive-splice idea remains informative, but adopting only that idea would be a new method, not a reproduction of the reported results. |
| [Kobayashi, Lookup Table Based Audio Spectral Transformation, DAFx 2025](https://dafx.de/paper-archive/2025/DAFx25_paper_79.pdf) | Visual frequency/amplitude mapping for creative effects, using an STFT backbone. No controlled superiority result for transparent TSM; reported demonstrations are mono 44.1 kHz. | Search hit is not a faster transparent time-stretch algorithm. Do not confuse these editable effect LUTs with the precomputed analysis tables in the 2026 playback paper. |

## External user/developer feedback

These are firsthand posts or maintainer explanations, **not controlled listening
studies or verified defects in today's library versions**. Old Audacity/Ardour
reports predate current R3 and must not be attributed to it. The linked videos
and SoundCloud tracks were not independently listened to in this investigation.

| Report | What the text actually supports | Comparison consequence |
| --- | --- | --- |
| [Audacity: Change tempo — worse when high quality (2017)](https://forum.audacityteam.org/t/change-tempo-worse-when-high-quality/45959) | A course narrator reports worse distortion with “high quality” at +10%, prefers the regular method and later +8%. Moderator explains a different tradeoff for percussion and output duration. | Include speech as well as percussion at wider rates. Do not assume a quality label determines the winner or turn one user's 8–10% preference into a universal threshold. |
| [Audacity: Tempo and artifacts (2011/2013)](https://forum.audacityteam.org/t/tempo-and-artifacts/19080) | User hears garbled piano/instruments but acceptable voice. A later oversampling-improves-quality claim is contradicted by a moderator. | Compare piano/polyphony and voice separately. Do not add expensive oversampling merely on an unreplicated anecdote. |
| [Ardour: Audio Stretching Quality (2019)](https://discourse.ardour.org/t/audio-stretching-quality/102223) | Vocal stretching is described as hollow. Maintainer notes algorithm/source dependence and implementation polish; no controlled ranking or exact rate is supplied. | Listen for vocal hollowness/phasiness and reverb, with identified rate/settings. Independent code is not presumed better than mature implementations. |
| [Ardour: Rubberband vs ElastiquePro (2024)](https://discourse.ardour.org/t/comparing-rubberband-in-ardour-to-elastiquepro-in-tracktion-waveform/109743) | Author says Rubber Band can give decent results and the complaint concerns editing implementation; another listener reports little difference. | Separate algorithm output from integration/usability. Do not cite this thread as proof one algorithm sounds worse. |
| [Ardour: Time Stretch vs Elastique Pro (2025)](https://discourse.ardour.org/t/take-note-of-this-time-stretch-vs-elastique-pro/111314) | A user reports being surprised by a blind video comparison and explicitly qualifies the interpretation. | Names can bias expectations. Our player displays names and per-case costs for usability at the user's request; it is not a controlled blind preference test. This anecdote does not validate our engines. |
| [Ardour: choosing libraries (2026)](https://discourse.ardour.org/t/ability-to-choose-which-time-stretch-library-to-use/113502) | Users want to know which implementation generic labels select; maintainer accepts clearer documentation. | Use explicit engine names and disclose parameters, not just “balanced” or “high quality.” |
| [Rubber Band/Pedalboard integration issue #54 (2022)](https://github.com/breakfastquay/rubberband/issues/54) | Developer observes startup dropouts at 1.25 pitch shift. Maintainer distinguishes output alignment delay from input required to produce output and explains blockwise availability. Closed as resolved. | Measure first-output input requirement separately from wall-clock processing; drain variable output properly. This is a streaming integration lesson, not evidence of an unavoidable R3 sound-quality defect or a TSM listening result. |

## Reproducible comparison

`tools/audio-comparison/render.py` runs the actual production JNI ABI for the
new engines and an externally preserved old library. `OhuRender.kt` compiles
production `PcmOverlap` and `OptimizedPcmOverlap`; it isolates the mixer's splice
and pacing, not its network/preroll/PLC/output-device policy. Therefore its CPU
row must not be called a whole-pipeline comparison with the native rows.

Use the same caller-provided stereo PCM16 WAVs for all engines. The script
applies one common input gain of 0.5 for headroom, never per-output normalization.
It generates named full-length WAVs, engine-labelled listening excerpts, input
and library hashes, timing/length measurements and a local HTML player. Old
libraries and copyrighted recordings remain outside the repository and APK.
Read the tool's README for commands and rate profiles.

Required distinctions when interpreting results:

- First-output input quantity, algorithmic output alignment, CPU execution time
  and hardware playback latency are different measurements.
- Retain old-engine synthesis tails rather than cutting every output to a guessed
  length. Record requested versus achieved duration separately.
- Unity reconstruction error is meaningful. At non-unity rates a raw PCM/SNR
  comparison without alignment is **not** an audible-quality score.
- Pitch, stereo isolation, antiphase behavior and transient timing catch specific
  failures; passing them cannot establish an overall listening preference.
- Host timings are not Android CPU savings, HAL compatibility or real-car results.
- A user preference must be collected from the rendered files, not inferred from
  the paper's age, algorithm name, a regression-test pass or another user's setup.

## Observed host comparison — 2026-10-24

The local listening set contains 728 processed outputs: 14 twelve-second inputs
at unity/current-policy/matched profiles across OHU, three old engines and four
independent engines, plus five musical/voice inputs at eight wider/stress profiles
across the seven engines supporting that range. All full-WAV PCM hashes and
listening excerpts were checked. The new engines produced the integrated target
duration in every rendered case. Unity PCM was bit-exact for all 14 inputs;
silence stayed zero, antiphase cancellation stayed exact, and there was no PCM16
clipping at the common −6.02 dB input gain. These facts do not establish perceptual
equivalence during correction.

Linux x86-64, GNU C++ 15.2.0 Release DSP, OpenJDK 21. One warm-up, then three runs
per input/profile. At constant tempo 1.01, the median across the 14 per-input
medians was:

| Engine | Thread CPU ms / input second | First-output source supply, 480-frame calls |
| --- | ---: | ---: |
| Previous SoundTouch | 3.49 | 100 ms |
| Previous R3 | 108.01 | 90 ms |
| Previous R3 Short | 31.80 | 50 ms |
| Independent WSOLA | 3.83 | 50 ms |
| Independent Phase Vocoder | 48.25 | 50 ms |
| Independent Phase Vocoder Short | 44.87 | 30 ms |
| Independent Phase Gradient | 73.68 | 60 ms |

The isolated OHU splice/pacing core measured 1.89 ms/input second; it is omitted
from the table because that core-only measurement is not a complete JNI
process/drain path. These are host observations, not device speedups. In
particular, **independent implementation is not automatically faster**:
SoundTouch cost less than the independent WSOLA median, and R3 Short cost less
than the independent spectral modes. The Short PV's smaller FFT is invoked more
often, so its CPU saving is much smaller than its startup-lookahead saving.

There are measurable quality limitations even at 1% correction. In the
55/110/220 Hz bass mixture, the 4-second spectral analysis found dominant peaks
near 55.5/111 Hz for WSOLA and PV Short, versus 55/110.5 Hz for PV and Phase
Gradient. The analysis-bin spacing is 0.25 Hz. These are particular mixture
peaks, not a uniform pitch-error model or a listening score. They expose why
short waveform search / short spectral windows must not be advertised as
universally pitch-transparent. Bass, sustained voices and piano remain necessary
listening cases despite passing the separate 440 Hz pitch regressions.

The deliberately extreme source-timed 0.5/2.0 alternation demonstrates a second
comparison limit: 12 seconds of input yielded 15.000 seconds from each new
source-clock-aware engine, versus 12.566 from the old SoundTouch adapter,
14.928 from R3 and 14.726 from R3 Short. The old references received identical
calls, but their internally buffered samples do not necessarily retain each
source block's assigned tempo. Do not rank these files as if they had identical
realized timing. Constant-rate comparisons and named full outputs are supplied
separately.

The branch also passed 4,047 JVM tests, 92 host-audio scenarios, the native
duration/pitch/stereo/boundary suite, 720 actual-JNI PCM comparisons, all four
Android ABI builds, APK source-asset checks and an address/undefined-behavior
sanitizer run of the endpoint-corrected DSP. The final reference-energy hoist
was subsequently covered by the native/JNI and host suites. Incremental asset
removal was exercised: the previous `Copy` task retained a removed generated
notice; replacing it with `Sync` removed it before APK packaging.

No on-device CPU measurement, manufacturer HAL test or subjective preference
result is claimed. The next selection input is the user's listening result,
not a presumed winner from this table.
