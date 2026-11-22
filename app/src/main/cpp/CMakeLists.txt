cmake_minimum_required(VERSION 3.22.1)
project(rubberband_jni LANGUAGES C CXX)

set(RB ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/rubberband)
set(RBS ${RB}/src)

add_library(rubberband-jni SHARED
    ${CMAKE_CURRENT_LIST_DIR}/RubberBandStretcherJNI.cpp
    ${RBS}/rubberband-c.cpp
    ${RBS}/RubberBandStretcher.cpp
    ${RBS}/RubberBandLiveShifter.cpp
    ${RBS}/faster/AudioCurveCalculator.cpp
    ${RBS}/faster/CompoundAudioCurve.cpp
    ${RBS}/faster/HighFrequencyAudioCurve.cpp
    ${RBS}/faster/SilentAudioCurve.cpp
    ${RBS}/faster/PercussiveAudioCurve.cpp
    ${RBS}/faster/R2Stretcher.cpp
    ${RBS}/faster/StretcherChannelData.cpp
    ${RBS}/faster/StretcherProcess.cpp
    ${RBS}/finer/R3Stretcher.cpp
    ${RBS}/finer/R3LiveShifter.cpp
    ${RBS}/common/Allocators.cpp
    ${RBS}/common/BQResampler.cpp
    ${RBS}/common/FFT.cpp
    ${RBS}/common/Log.cpp
    ${RBS}/common/Profiler.cpp
    ${RBS}/common/Resampler.cpp
    ${RBS}/common/StretchCalculator.cpp
    ${RBS}/common/VectorOpsComplex.cpp
    ${RBS}/common/sysutils.cpp
    ${RBS}/common/mathmisc.cpp
    ${RBS}/common/Thread.cpp
    ${RBS}/ext/kissfft/kiss_fft.c
    ${RBS}/ext/kissfft/kiss_fftr.c
)

target_include_directories(rubberband-jni PRIVATE
    ${RB}
    ${RBS}
    ${RBS}/ext/kissfft
)
target_compile_features(rubberband-jni PRIVATE cxx_std_17)
target_compile_definitions(rubberband-jni PRIVATE
    USE_BQRESAMPLER
    USE_BUILTIN_FFT
    LACK_POSIX_MEMALIGN
    USE_OWN_ALIGNED_MALLOC
    LACK_SINCOS
    NO_EXCEPTIONS
    NO_THREADING
    NO_THREAD_CHECKS
)
target_compile_options(rubberband-jni PRIVATE -O3 -ffast-math -ftree-vectorize)
target_link_libraries(rubberband-jni PRIVATE log android)
