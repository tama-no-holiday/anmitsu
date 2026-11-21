# zenz model and llama.cpp runtime

**State in anmitsu 1.0.0.** The APK bundles two files in `assets/`, both pinned by SHA-256 in
`android/app/zenz-assets.lock`: the zenz-v3.2-xsmall Q5_K_M GGUF, unmodified, and `arm64-v8a/libastra_zenz.so`, built
from `android/zenz-native` and the unmodified llama.cpp v0.7.0 source (recipe below). Neither file is in the Git source.
The app loads them only after its CPU check (`android/app/src/main/java/dev/astra/ime/ZenzCpu.kt`); otherwise it converts
with the dictionary alone. No training or fine-tuning was done. Notices shipped in the APK: `zenz-LICENSE-Apache-2.0.txt`
(from `Apache-2.0.txt` here), `llama.cpp-LICENSE.txt` and `llama.cpp-LICENSE-jsonhpp.txt`. Open licence questions are
listed in [THIRD-PARTY.md](../../THIRD-PARTY.md); how to obtain and build the files is in [BUILDING.md](../../BUILDING.md)
section 4.

## Evaluation download (historical, 2026-10-27)

The files below were downloaded with the owner's approval into an ignored local directory for evaluation. Of the two
models, only xsmall is bundled; the small model was evaluated and is not shipped. The build tools are not shipped.

| Item | Source (pinned) | SHA-256 | License |
|---|---|---|---|
| zenz-v3.2-xsmall Q5_K_M GGUF (20,970,304 B) | huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf @ `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83` | `00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58` (matches the Hub LFS oid) | Apache-2.0 (Hub tag; README is near empty) |
| zenz-v3.2-small Q5_K_M GGUF (73,871,936 B) | huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf @ `c67e03e07d215c869f591b274c1631170d3e11fe` | `29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673` (matches) | Apache-2.0 (Hub tag) |
| llama.cpp source v0.7.0 (zip) | github.com/ggml-org/llama.cpp tag `v0.7.0` | `48ec218be461975cd4d39f2155675851e6f3ad7835f2522412ec2b610f71c20c` (recorded; GitHub publishes no digest for source archives) | MIT |
| Android NDK r30 (Windows) | dl.google.com/android/repository/android-ndk-r30-windows.zip | `b830098aaf18b67a42eb831c404e15e5f2990a474f054ac145b0bc957ac6d729`; SHA-1 `9bf167a1…9408` matches developer.android.com | Android SDK license |
| CMake 4.4.4 (Windows zip) | github.com/Kitware/CMake release v4.4.4 | `bace36e9…dac536` (matches release digest) | BSD-3-Clause |
| Ninja 1.13.2 (Windows zip) | github.com/ninja-build/ninja release v1.13.2 | `07fc8261…dc65` (matches) | Apache-2.0 |
| w64devkit 2.10.1 (host gcc, extracted with tar, never executed as an installer) | github.com/skeeto/w64devkit release v2.10.1 | `add1edf9…896a` (matches) | Unlicense / component licenses |

The zenz model card does not describe the training data. The model metadata names ku-nlp gpt2-small-japanese-char as
the base (general.organization "Ku Nlp" in the small GGUF). The provenance check that `docs/model-comparison.md` asks for
(the Apache tag alone does not establish the training data's provenance) has not been completed; see
[THIRD-PARTY.md](../../THIRD-PARTY.md).

## Integration notes

- `android/zenz-native`: C++ scorer over the llama.cpp C API (`zenz_scorer.cpp`), a JNI bridge and a host CLI.
  llama.cpp is compiled from the pinned source without modification.
- The GGUFs declare the pre-tokenizer `gpt2-small-japanese-char`, unknown to upstream llama.cpp. The scorer
  overrides `tokenizer.ggml.pre` at load time (`kv_overrides`) and tokenizes one character at a time so each
  vocabulary character maps to one token (checked: 7 chars → 7 tokens). U+EE00–U+EE02 are not in the
  vocabulary and become byte tokens, as with the original byte-level tokenizer.
- `</s>` is looked up as the end token; the GGUF `eos_token_id` field points at another id.

## Building the Android library

No script does this. With NDK r30, CMake 4.4.4 and Ninja from the table above, in a short fresh directory (Ninja's incremental stat breaks over 260 characters on Windows):

```
cmake -S android/zenz-native -B <out> -G Ninja -DCMAKE_MAKE_PROGRAM=<ninja> -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DLLAMA_DIR=<llama.cpp-0.7.0>
cmake --build <out> --target astra_zenz
llvm-strip --strip-unneeded <out>/libastra_zenz.so -o <dir>/arm64-v8a/libastra_zenz.so
```

Then put the new hash in `android/app/zenz-assets.lock`. Historical check (0.1.28): this recipe on the 0.1.5 scorer source reproduced the library locked for 0.1.5 to 0.1.27 (`82c60356…`) in every section and in all 1,016,755 disassembled lines,
with `GIT_EXE` pointed at a stub so that ggml's stamped commit is the old `1a82356`; only the build-id differs. Whether the 1.0.0 library (`91a88be6…`) is reproduced byte for byte on another machine has not been checked.

**`armv8.2-a+dotprod+fp16` means the library needs those CPU features, while the app's minSdk is 26.** Loading it on a CPU without them would stop the process on an illegal instruction. Since 1.0.0 the app therefore loads it only in a 64-bit ARM process whose kernel reports every required feature (`ZenzCpu.kt`); on any other device, or when the answer cannot be read, it does not load the library and converts with the dictionary alone. Which phones lack the features was not checked.

The host build (`zenz-cache-check`, the JVM evaluation library) uses w64devkit, the same CMake call without the toolchain and ABI lines, `-DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ -DJAVA_HOME=<jdk>`.
`zenz-cache-check <model.gguf> [threads]` must print PASSED: scores may not depend on what the scorer did before. Run it after any change to `zenz_scorer.cpp` or llama.cpp.
