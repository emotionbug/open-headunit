#include "audio_time_stretcher.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <limits>
#include <stdexcept>

namespace {
constexpr double pi = 3.14159265358979323846;
constexpr size_t inputCapacity = 16384;
constexpr size_t overlapCapacity = 4096;
constexpr int maximumWindow = 2048;
using Complex = std::complex<double>;
double principal(double phase) { return std::remainder(phase, 2 * pi); }
int16_t quantize(double sample) {
    if (!std::isfinite(sample)) throw std::runtime_error("Non-finite time-stretch output");
    return static_cast<int16_t>(std::lrint(std::clamp(sample, -32768.0, 32767.0)));
}
}

// Independently implemented from the equations cited in the native test README.
// All storage is acquired at construction. receive() performs bounded-window work
// on demand, so a slow consumer cannot create an unbounded synthesized-output queue.
struct AudioTimeStretcher::Impl {
    Engine engine;
    int windowSize, hop, search;
    std::array<std::array<int16_t, 2>, inputCapacity> source{};
    std::array<double, inputCapacity> sourceEndTime{};
    std::array<std::array<double, 2>, overlapCapacity> overlap{};
    std::array<double, overlapCapacity> weight{};
    std::array<double, maximumWindow> window{};
    std::array<std::array<Complex, maximumWindow>, 2> spectrum{};
    std::array<Complex, maximumWindow / 2> roots{};
    std::array<unsigned, maximumWindow> reversed{};
    std::array<std::array<double, maximumWindow / 2 + 1>, 2> previousPhase{};
    std::array<double, maximumWindow / 2 + 1> previousMagnitude{}, magnitude{}, rotation{}, nextRotation{};
    std::array<int, maximumWindow / 2 + 1> peaks{};
    struct Gradient {
        std::array<std::array<Complex, maximumWindow>, 2> future{};
        std::array<std::array<double, maximumWindow / 2 + 1>, 2> phase{}, time{}, previousTime{}, frequency{};
        std::array<bool, maximumWindow / 2 + 1> pending{};
        struct Entry { double magnitude; int bin; bool current; };
        std::array<Entry, maximumWindow + 2> heap{};
        bool modified = false;
    };
    std::unique_ptr<Gradient> gradient;
    int64_t begin = 0, end = 0, timeCursor = 0;
    int64_t synthesisCenter = 0, emitted = 0, ready = 0;
    int64_t previousCenter = 0, adjustedCenter = 0;
    double duration = 0, durationError = 0, lastTempo = 1;
    bool final = false, first = true, endpointAdded = false;

    explicit Impl(Engine selected) : engine(selected), windowSize(0), hop(0), search(0) {
        switch (engine) {
            case Engine::Wsola: windowSize = 1920; hop = 480; search = 240; break;
            case Engine::PhaseVocoder: windowSize = 2048; hop = 512; break;
            case Engine::PhaseVocoderShort: windowSize = 1024; hop = 256; break;
            case Engine::PhaseGradient:
                windowSize = 2048; hop = 512; gradient = std::make_unique<Gradient>(); break;
            default: throw std::invalid_argument("Unknown time stretcher");
        }
        for (int i = 0; i < windowSize; ++i)
            window[i] = 0.5 - 0.5 * std::cos(2 * pi * i / windowSize);
        if (!search) {
            unsigned bits = 0;
            while ((1U << bits) < static_cast<unsigned>(windowSize)) ++bits;
            for (int i = 0; i < windowSize; ++i) {
                unsigned value = static_cast<unsigned>(i), reverse = 0;
                for (unsigned b = 0; b < bits; ++b) { reverse = (reverse << 1) | (value & 1); value >>= 1; }
                reversed[i] = reverse;
            }
            for (int i = 0; i < windowSize / 2; ++i)
                roots[i] = std::polar(1.0, -2 * pi * i / windowSize);
        }
    }

    double sample(int64_t position, int channel) const {
        if (position < 0 || position >= end) return 0;
        if (position < begin) throw std::logic_error("Time-stretch source history exhausted");
        return source[static_cast<size_t>(position) % inputCapacity][channel];
    }
    int64_t target() const { return static_cast<int64_t>(std::llround(duration)); }

    // Invert the piecewise-linear integral of 1/tempo. Tempo belongs to the source
    // frames accepted by process(), not to whichever block happens to unlock FFT
    // lookahead. The last ratio extends only the zero padding after final source.
    double sourcePosition(double time) {
        if (time >= duration) return end + (time - duration) * lastTempo;
        while (timeCursor < end && sourceEndTime[static_cast<size_t>(timeCursor) % inputCapacity] <= time)
            ++timeCursor;
        const double stop = sourceEndTime[static_cast<size_t>(timeCursor) % inputCapacity];
        const double start = timeCursor == 0 ? 0 : sourceEndTime[static_cast<size_t>(timeCursor - 1) % inputCapacity];
        return timeCursor + (time - start) / (stop - start);
    }

    void fft(std::array<Complex, maximumWindow>& data, bool inverse) const {
        for (int i = 0; i < windowSize; ++i)
            if (static_cast<unsigned>(i) < reversed[i]) std::swap(data[i], data[reversed[i]]);
        for (int length = 2; length <= windowSize; length *= 2) {
            const int half = length / 2, stride = windowSize / length;
            for (int start = 0; start < windowSize; start += length)
                for (int j = 0; j < half; ++j) {
                    const Complex root = inverse ? std::conj(roots[j * stride]) : roots[j * stride];
                    const Complex even = data[start + j], odd = data[start + j + half] * root;
                    data[start + j] = even + odd;
                    data[start + j + half] = even - odd;
                }
        }
        if (inverse) for (int i = 0; i < windowSize; ++i) data[i] /= windowSize;
    }

    int64_t alignWaveform(int64_t nominal) const {
        if (first) return nominal;
        const int64_t natural = adjustedCenter + hop;
        const int64_t low = std::max<int64_t>(0, nominal - search);
        const int64_t high = nominal + search;
        // Matching natural progression exactly is preferable to any periodic alias.
        // The absolute nominal bounds prevent local similarity from accumulating
        // unlimited timing drift.
        if (natural >= low && natural <= high) return natural;
        const int length = windowSize - hop;
        const int64_t reference = natural - windowSize / 2;
        double referenceEnergy = 0;
        for (int i = 0; i < length; i += 4) for (int ch = 0; ch < 2; ++ch) {
            const double x = sample(reference + i, ch);
            referenceEnergy += x * x;
        }
        if (referenceEnergy == 0) return nominal;
        auto score = [&](int64_t center) {
            double dot = 0, b = 0;
            for (int i = 0; i < length; i += 4) for (int ch = 0; ch < 2; ++ch) {
                const double x = sample(reference + i, ch);
                const double y = sample(center - windowSize / 2 + i, ch);
                dot += x * y; b += y * y;
            }
            // Joint-channel energy, never L+R: antiphase material must not cancel.
            return b > 0 ? dot / std::sqrt(referenceEnergy * b) : -1.0;
        };
        int64_t best = nominal;
        double bestScore = score(best);
        for (int64_t candidate = low; candidate <= high; candidate += 4) {
            const double candidateScore = score(candidate);
            if (candidateScore > bestScore + 1e-12) { bestScore = candidateScore; best = candidate; }
        }
        const int64_t coarse = best;
        for (int64_t candidate = std::max(low, coarse - 3); candidate <= std::min(high, coarse + 3); ++candidate) {
            const double candidateScore = score(candidate);
            if (candidateScore > bestScore + 1e-12) { bestScore = candidateScore; best = candidate; }
        }
        return best;
    }

    void phaseVocoder(int64_t center) {
        const int bins = windowSize / 2 + 1;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < windowSize; ++i)
                spectrum[ch][i] = sample(center - windowSize / 2 + i, ch) * window[i];
            fft(spectrum[ch], false);
        }
        double flux = 0, previousTotal = 0;
        for (int k = 0; k < bins; ++k) {
            magnitude[k] = std::sqrt(std::norm(spectrum[0][k]) + std::norm(spectrum[1][k]));
            flux += std::max(0.0, magnitude[k] - previousMagnitude[k]);
            previousTotal += previousMagnitude[k];
        }
        // A shared positive spectral-flux onset resets vertical phase coherence.
        // This conservative heuristic is not harmonic/percussive separation.
        const bool transient = first || (flux > 0.35 * previousTotal && flux > 1.0);
        int peakCount = 0;
        for (int k = 1; k < bins - 1; ++k)
            if (magnitude[k] > magnitude[k - 1] && magnitude[k] >= magnitude[k + 1]) peaks[peakCount++] = k;
        if (!peakCount) peaks[peakCount++] = static_cast<int>(std::max_element(magnitude.begin(), magnitude.begin() + bins) - magnitude.begin());
        int regionStart = 0;
        const double analysisHop = static_cast<double>(center - previousCenter);
        for (int p = 0; p < peakCount; ++p) {
            const int peak = peaks[p];
            const int regionEnd = p + 1 < peakCount ? (peak + peaks[p + 1]) / 2 + 1 : bins;
            const int referenceChannel = std::norm(spectrum[0][peak]) >= std::norm(spectrum[1][peak]) ? 0 : 1;
            double angle = 0;
            if (!transient) {
                const double phase = std::arg(spectrum[referenceChannel][peak]);
                const double advance = 2 * pi * peak * analysisHop / windowSize;
                const double unwrapped = advance + principal(phase - previousPhase[referenceChannel][peak] - advance);
                angle = principal(rotation[peak] + unwrapped * (hop / analysisHop - 1));
            }
            // Laroche/Dolson identity locking: a common complex rotation for the
            // peak and its region. Sharing it across L/R preserves interchannel
            // phase differences, silence, and exact antiphase within each frame.
            const Complex phasor = std::polar(1.0, angle);
            for (int k = regionStart; k < regionEnd; ++k) {
                nextRotation[k] = angle;
                for (int ch = 0; ch < 2; ++ch) {
                    previousPhase[ch][k] = std::arg(spectrum[ch][k]);
                    spectrum[ch][k] *= phasor;
                }
            }
            regionStart = regionEnd;
        }
        for (int k = 0; k < bins; ++k) { rotation[k] = nextRotation[k]; previousMagnitude[k] = magnitude[k]; }
        for (int ch = 0; ch < 2; ++ch) {
            // DC and Nyquist must be real for a real-valued inverse transform.
            spectrum[ch][0] = Complex(spectrum[ch][0].real(), 0);
            spectrum[ch][bins - 1] = Complex(spectrum[ch][bins - 1].real(), 0);
            for (int k = 1; k < bins - 1; ++k) spectrum[ch][windowSize - k] = std::conj(spectrum[ch][k]);
            fft(spectrum[ch], true);
        }
    }

    void phaseGradient(int64_t center, int64_t futureCenter) {
        auto& g = *gradient;
        const int bins = windowSize / 2 + 1;
        for (int ch = 0; ch < 2; ++ch) {
            if (first) {
                for (int i = 0; i < windowSize; ++i)
                    spectrum[ch][i] = sample(center - windowSize / 2 + i, ch) * window[i];
                fft(spectrum[ch], false);
            } else {
                std::copy_n(g.future[ch].begin(), windowSize, spectrum[ch].begin());
            }
            for (int i = 0; i < windowSize; ++i)
                g.future[ch][i] = sample(futureCenter - windowSize / 2 + i, ch) * window[i];
            fft(g.future[ch], false);
            for (int k = 0; k < bins; ++k) {
                // Convert the FFT's left-edge origin to the paper's centered STFT.
                g.phase[ch][k] = principal(std::arg(spectrum[ch][k]) + pi * k);
                const double omega = 2 * pi * k / windowSize;
                const double forwardHop = static_cast<double>(futureCenter - center);
                const double forward = omega + principal(std::arg(g.future[ch][k]) + pi * k -
                    g.phase[ch][k] - omega * forwardHop) / forwardHop;
                const double backwardHop = static_cast<double>(center - previousCenter);
                const double backward = first ? forward : omega + principal(g.phase[ch][k] -
                    previousPhase[ch][k] - omega * backwardHop) / backwardHop;
                g.time[ch][k] = (forward + backward) * 0.5;
            }
            for (int k = 0; k < bins; ++k) {
                const double backward = k ? principal(g.phase[ch][k] - g.phase[ch][k - 1]) :
                    principal(g.phase[ch][1] - g.phase[ch][0]);
                const double forward = k + 1 < bins ? principal(g.phase[ch][k + 1] - g.phase[ch][k]) : backward;
                g.frequency[ch][k] = (backward + forward) * 0.5;
            }
        }
        double maximum = 0;
        for (int k = 0; k < bins; ++k) {
            magnitude[k] = std::sqrt(std::norm(spectrum[0][k]) + std::norm(spectrum[1][k]));
            maximum = std::max({maximum, magnitude[k], previousMagnitude[k]});
            nextRotation[k] = 0; // Deterministic original phase below the relative noise threshold.
        }
        g.modified = g.modified || center != synthesisCenter || futureCenter != synthesisCenter + hop;
        if (!first && g.modified) {
            size_t heapSize = 0;
            auto less = [](const Gradient::Entry& a, const Gradient::Entry& b) {
                if (a.magnitude != b.magnitude) return a.magnitude < b.magnitude;
                if (a.bin != b.bin) return a.bin > b.bin;
                return a.current < b.current;
            };
            auto push = [&](int bin, bool current) {
                g.heap[heapSize++] = {current ? magnitude[bin] : previousMagnitude[bin], bin, current};
                std::push_heap(g.heap.begin(), g.heap.begin() + heapSize, less);
            };
            for (int k = 0; k < bins; ++k) {
                g.pending[k] = magnitude[k] > maximum * 1e-6;
                if (g.pending[k]) push(k, false);
            }
            const double alpha = 2.0 * hop / (futureCenter - previousCenter);
            while (heapSize) {
                std::pop_heap(g.heap.begin(), g.heap.begin() + heapSize, less);
                const auto entry = g.heap[--heapSize];
                const int k = entry.bin;
                if (!entry.current) {
                    if (!g.pending[k]) continue;
                    const int ch = std::norm(spectrum[0][k]) >= std::norm(spectrum[1][k]) ? 0 : 1;
                    nextRotation[k] = principal(previousPhase[ch][k] + rotation[k] +
                        hop * 0.5 * (g.previousTime[ch][k] + g.time[ch][k]) - g.phase[ch][k]);
                    g.pending[k] = false;
                    push(k, true);
                } else {
                    for (int direction : {-1, 1}) {
                        const int neighbor = k + direction;
                        if (neighbor < 0 || neighbor >= bins || !g.pending[neighbor]) continue;
                        const int ch = std::norm(spectrum[0][k]) + std::norm(spectrum[0][neighbor]) >=
                            std::norm(spectrum[1][k]) + std::norm(spectrum[1][neighbor]) ? 0 : 1;
                        nextRotation[neighbor] = principal(g.phase[ch][k] + nextRotation[k] +
                            direction * alpha * 0.5 * (g.frequency[ch][k] + g.frequency[ch][neighbor]) -
                            g.phase[ch][neighbor]);
                        g.pending[neighbor] = false;
                        push(neighbor, true);
                    }
                }
            }
        }
        for (int k = 0; k < bins; ++k) {
            const Complex phasor = std::polar(1.0, nextRotation[k]);
            for (int ch = 0; ch < 2; ++ch) {
                spectrum[ch][k] *= phasor;
                previousPhase[ch][k] = g.phase[ch][k];
                g.previousTime[ch][k] = g.time[ch][k];
            }
            rotation[k] = nextRotation[k];
            previousMagnitude[k] = magnitude[k];
        }
        for (int ch = 0; ch < 2; ++ch) {
            spectrum[ch][0] = Complex(spectrum[ch][0].real(), 0);
            spectrum[ch][bins - 1] = Complex(spectrum[ch][bins - 1].real(), 0);
            for (int k = 1; k < bins - 1; ++k) spectrum[ch][windowSize - k] = std::conj(spectrum[ch][k]);
            fft(spectrum[ch], true);
        }
    }

    void addEndpointFrame() {
        const int64_t outputCenter = target() - 1;
        const int64_t inputCenter = end - 1;
        // Finite-stream boundary condition: an identity-phase analysis frame
        // centered on the final source sample is centered on the final output
        // sample. Hop-grid phase propagation alone can place an onset just beyond
        // the rounded output boundary. This regular, signal-independent OLA term
        // keeps that boundary represented without extra output or resampling.
        for (int i = 0; i < windowSize; ++i) {
            const int64_t position = outputCenter - windowSize / 2 + i;
            if (position < emitted || position > outputCenter) continue;
            const size_t slot = static_cast<size_t>(position) % overlapCapacity;
            const double w = search ? window[i] : window[i] * window[i];
            weight[slot] += w;
            for (int ch = 0; ch < 2; ++ch)
                overlap[slot][ch] += sample(inputCenter - windowSize / 2 + i, ch) * w;
        }
        endpointAdded = true;
    }

    bool synthesize() {
        if (!end || (final && emitted >= target())) return false;
        if (final && !endpointAdded && synthesisCenter >= target() - 1) {
            addEndpointFrame();
            ready = std::min(target(), std::max(emitted, synthesisCenter - windowSize / 2));
            return true;
        }
        if (!final && synthesisCenter >= duration) return false;
        const int64_t center = static_cast<int64_t>(std::llround(sourcePosition(static_cast<double>(synthesisCenter))));
        int64_t futureCenter = center;
        if (gradient) {
            if (!final && synthesisCenter + hop >= duration) return false;
            const auto savedCursor = timeCursor;
            futureCenter = static_cast<int64_t>(std::llround(sourcePosition(static_cast<double>(synthesisCenter + hop))));
            timeCursor = savedCursor;
        }
        if (!final && futureCenter + windowSize / 2 + search > end) return false;
        const int64_t selected = search ? alignWaveform(center) : center;
        if (gradient) phaseGradient(center, futureCenter);
        else if (!search) phaseVocoder(center);
        const auto finalLength = final ? target() : std::numeric_limits<int64_t>::max();
        for (int i = 0; i < windowSize; ++i) {
            const int64_t position = synthesisCenter - windowSize / 2 + i;
            if (position < emitted || position >= finalLength) continue;
            const size_t slot = static_cast<size_t>(position) % overlapCapacity;
            const double w = window[i];
            weight[slot] += search ? w : w * w;
            for (int ch = 0; ch < 2; ++ch)
                overlap[slot][ch] += search ? sample(selected - windowSize / 2 + i, ch) * w : spectrum[ch][i].real() * w;
        }
        previousCenter = center;
        adjustedCenter = selected;
        first = false;
        synthesisCenter += hop;
        const int64_t nextCenter = final && !endpointAdded ?
            std::min(synthesisCenter, target() - 1) : synthesisCenter;
        ready = std::max<int64_t>(emitted, nextCenter - windowSize / 2);
        if (final) ready = std::min(ready, target());
        // Retain the previous natural-progression overlap, the next nominal search
        // range, and one source-clock boundary for inversion. No memmove is needed.
        const int64_t retain = std::min({center - windowSize / 2 - search - 2,
                                        selected + hop - windowSize / 2,
                                        timeCursor - 1});
        begin = std::max(begin, std::max<int64_t>(0, retain));
        return true;
    }
};

AudioTimeStretcher::AudioTimeStretcher(Engine engine) : impl(std::make_unique<Impl>(engine)) {}
AudioTimeStretcher::~AudioTimeStretcher() = default;
void AudioTimeStretcher::process(const int16_t* pcm, size_t frames, double tempo) {
    auto& p = *impl;
    if (!pcm || p.final || frames == 0 || frames > 480 || !std::isfinite(tempo) || tempo < 0.5 || tempo > 2.0)
        throw std::invalid_argument("Invalid PCM time-stretch input");
    if (frames > inputCapacity - static_cast<size_t>(p.end - p.begin))
        throw std::overflow_error("Time-stretch input full; receive output before retrying");
    if (p.end > std::numeric_limits<int64_t>::max() / 2 - 8192)
        throw std::overflow_error("Time-stretch stream too long");
    const double step = 1.0 / tempo;
    for (size_t i = 0; i < frames; ++i) {
        const size_t slot = static_cast<size_t>(p.end++) % inputCapacity;
        p.source[slot] = {pcm[i * 2], pcm[i * 2 + 1]};
        const double increment = step - p.durationError;
        const double next = p.duration + increment;
        p.durationError = (next - p.duration) - increment;
        p.duration = next;
        p.sourceEndTime[slot] = p.duration;
    }
    p.lastTempo = tempo;
}
void AudioTimeStretcher::finish() { impl->final = true; }
size_t AudioTimeStretcher::receive(int16_t* pcm, size_t capacityFrames) {
    if (!pcm && capacityFrames) throw std::invalid_argument("Invalid PCM output");
    auto& p = *impl;
    size_t written = 0;
    while (written < capacityFrames) {
        if (p.emitted == p.ready) { if (!p.synthesize()) break; else continue; }
        const size_t slot = static_cast<size_t>(p.emitted) % overlapCapacity;
        for (int ch = 0; ch < 2; ++ch) {
            pcm[written * 2 + ch] = quantize(p.weight[slot] > 0 ? p.overlap[slot][ch] / p.weight[slot] : 0);
            p.overlap[slot][ch] = 0;
        }
        p.weight[slot] = 0;
        ++p.emitted; ++written;
    }
    return written;
}
bool AudioTimeStretcher::finished() const { return impl->final && impl->emitted == impl->target(); }
