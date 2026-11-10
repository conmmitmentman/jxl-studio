# Local fixes to zune-jpegxl 0.5.2

Source: the `zune-jpegxl` 0.5.2 package from crates.io.
Upstream: https://github.com/etemesi254/zune-image/tree/dev/crates/zune-jpegxl
Original copyright headers and license files are retained.

Changes in `src/encoder.rs`:

1. Permit width/height 1 while continuing to reject zero. The JPEG XL size
   header encodes `size - 1`, so a dimension of 1 is representable.
2. Write explicit integer 16-bit alpha extra-channel metadata when encoding
   RGBA16 or gray-alpha16. The upstream code always signals the default
   8-bit alpha channel, causing 16-bit alpha values to be incorrectly scaled
   or clamped by a conforming decoder.

These changes are covered by `tests/roundtrip.rs`, using the independent
`jxl-oxide` decoder. No modifications are made to the shared Cargo registry.

3. Read gray-alpha 16-bit samples as unsigned `u16` in `color_convert.rs`,
   matching the other 16-bit pixel layouts. Reading as `i16` made samples
   above 32767 negative and caused loss of their upper half-range.
4. Remove an ineffective crate-level `macro_use` attribute that is deprecated
   by current Rust compilers.
