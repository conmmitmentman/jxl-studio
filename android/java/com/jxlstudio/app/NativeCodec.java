package com.jxlstudio.app;

/** The same Rust codec used by the desktop application. Calls belong on a worker thread. */
public final class NativeCodec {
    static { System.loadLibrary("jxl_studio_android"); }
    private NativeCodec() {}
    public static native String nativeConvert(String inputPath, String outputPath);
    public static native String nativePreview(String inputPath, String outputPngPath, int maxSide);
    public static native String nativeExportPng(String inputPath, String outputPath);
}
