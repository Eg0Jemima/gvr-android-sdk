package com.google.vr.ndk.samples.hellovrbeta;

public class LatencyCollector {
    public static native void DecoderInput(long frameIndex);
    public static native void DecoderOutput(long frameIndex);
}
