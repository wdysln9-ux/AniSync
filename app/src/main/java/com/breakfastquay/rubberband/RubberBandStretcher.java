package com.breakfastquay.rubberband;

/** JNI wrapper for the upstream Rubber Band real-time stretcher. */
public final class RubberBandStretcher {
    public RubberBandStretcher(int sampleRate, int channels, int options,
                               double initialTimeRatio, double initialPitchScale) {
        handle = 0;
        initialise(sampleRate, channels, options, initialTimeRatio, initialPitchScale);
    }
    public native void dispose();
    public native void reset();
    public native void setTimeRatio(double ratio);
    public native void setPitchScale(double scale);
    public native int getChannelCount();
    public native double getTimeRatio();
    public native double getPitchScale();
    public native int getLatency();
    public native void setTransientsOption(int options);
    public native void setDetectorOption(int options);
    public native void setPhaseOption(int options);
    public native void setFormantOption(int options);
    public native void setPitchOption(int options);
    public native void setExpectedInputDuration(long samples);
    public native void setMaxProcessSize(int samples);
    public native int getSamplesRequired();
    public native void study(float[][] input, int offset, int n, boolean finalBlock);
    public void study(float[][] input, boolean finalBlock) { study(input, 0, input[0].length, finalBlock); }
    public native void process(float[][] input, int offset, int n, boolean finalBlock);
    public void process(float[][] input, boolean finalBlock) { process(input, 0, input[0].length, finalBlock); }
    public native int available();
    public native int retrieve(float[][] output, int offset, int n);
    public int retrieve(float[][] output) { return retrieve(output, 0, output[0].length); }
    private native void initialise(int sampleRate, int channels, int options,
                                   double initialTimeRatio, double initialPitchScale);
    private long handle;
    public static final int OptionProcessRealTime = 0x00000001;
    public static final int OptionThreadingNever = 0x00010000;
    public static final int OptionChannelsTogether = 0x10000000;
    static { System.loadLibrary("rubberband-jni"); }
}
