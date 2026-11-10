# Third-party notices

JXL Studio's own source code is distributed under the MIT license.
Dependency versions are recorded in `Cargo.lock`. Key libraries include:

| Component | Project | License |
| --- | --- | --- |
| GUI | egui / eframe | MIT OR Apache-2.0 |
| Image formats | image | MIT OR Apache-2.0 |
| JPEG XL encoder | zune-jpegxl / zune-core | MIT OR Apache-2.0 OR Zlib |
| JPEG XL decoder | jxl-oxide and its jxl-* crates | MIT OR Apache-2.0 |
| Color management | moxcms | BSD-3-Clause OR Apache-2.0 |
| Native dialogs | rfd | MIT |
| Temporary files | tempfile | MIT OR Apache-2.0 |
| Error handling | anyhow | MIT OR Apache-2.0 |

The distribution contains a `licenses` directory with the license and notice
files of the packages in the locked dependency graph. This includes the fonts
bundled by eframe, such as Ubuntu and Hack. The additional Japanese system font
is loaded from the user's OS and is not redistributed by this project.

See each package's upstream source for its complete copyright and license terms.
