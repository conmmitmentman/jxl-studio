package com.jxlstudio.app;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Real Java -> JNI -> Rust -> JXL -> PNG round trip, runnable without Android hardware. */
public final class JniSmokeTest {
    private static void success(String response) {
        if (response == null || !response.contains("\"ok\":true")) throw new AssertionError(response);
    }
    private static void failure(String response) {
        if (response == null || !response.contains("\"ok\":false")) throw new AssertionError(response);
    }
    private static void same(BufferedImage expected, BufferedImage actual) {
        if (actual == null || expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) throw new AssertionError("Dimensions changed");
        for (int y = 0; y < expected.getHeight(); y++) for (int x = 0; x < expected.getWidth(); x++) {
            if (expected.getRGB(x, y) != actual.getRGB(x, y)) throw new AssertionError("Pixel mismatch at " + x + "," + y);
        }
    }
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("jxl-jni-").toFile();
        File input = new File(directory, "透過テスト.png");
        File jxl = new File(directory, "converted.jxl");
        File preview = new File(directory, "preview.png");
        File exported = new File(directory, "export.png");
        BufferedImage pixels = new BufferedImage(17, 19, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 19; y++) for (int x = 0; x < 17; x++) {
            pixels.setRGB(x, y, ((x * y) & 255) << 24 | ((x * 13) & 255) << 16 | ((y * 11) & 255) << 8 | 123);
        }
        ImageIO.write(pixels, "png", input);
        success(NativeCodec.nativeConvert(input.getPath(), jxl.getPath()));
        success(NativeCodec.nativePreview(jxl.getPath(), preview.getPath(), 2048));
        same(pixels, ImageIO.read(preview));
        success(NativeCodec.nativeExportPng(jxl.getPath(), exported.getPath()));
        same(pixels, ImageIO.read(exported));
        failure(NativeCodec.nativeConvert(input.getPath(), jxl.getPath()));
        failure(NativeCodec.nativeExportPng(jxl.getPath(), exported.getPath()));
        failure(NativeCodec.nativePreview(input.getPath(), input.getPath(), 2048));
        failure(NativeCodec.nativePreview(input.getPath(), preview.getPath(), 0));
        same(pixels, ImageIO.read(input));
        success(NativeCodec.nativePreview(jxl.getPath(), preview.getPath(), 8));
        BufferedImage small = ImageIO.read(preview);
        if (small.getWidth() > 8 || small.getHeight() > 8) throw new AssertionError("Preview size limit ignored");
        File broken = new File(directory, "broken.jxl");
        Files.write(broken.toPath(), new byte[]{1, 2, 3});
        failure(NativeCodec.nativePreview(broken.getPath(), preview.getPath(), 2048));
        System.out.println("JNI smoke test passed: conversion, RGBA pixels, preview, PNG export, Unicode, limits and no-clobber errors.");
    }
}
