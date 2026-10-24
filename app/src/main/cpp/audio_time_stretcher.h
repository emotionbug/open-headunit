#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

// One worker owns each instance. PCM is 48 kHz interleaved stereo; counts are FRAMES.
// process() accepts at most 480 frames, tempo 0.5..2.0. Call receive() between
// blocks; bounded input exhaustion throws before accepting any of a block.
// finish() is idempotent. Drain receive() until finished(); duration is the rounded
// integral of source-frame 1/tempo, including short sources and the final block.
class AudioTimeStretcher {
public:
    enum class Engine { Wsola = 1, PhaseVocoder = 2, PhaseVocoderShort = 3, PhaseGradient = 4 };
    explicit AudioTimeStretcher(Engine engine);
    ~AudioTimeStretcher();
    void process(const int16_t* pcm, size_t frames, double tempo);
    void finish();
    size_t receive(int16_t* pcm, size_t capacityFrames);
    bool finished() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
