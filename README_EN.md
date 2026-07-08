<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:a8c0ff,100:3f2b96&height=150&section=header&text=LSPlantPro&fontSize=40&fontColor=ffffff&animation=fadeIn&desc=Prebuilt%20prefab%20Maven%20source%20of%20LSPlant%20for%20Android%2016%20%2F%20API%2036&descSize=14&descAlignY=58" alt="LSPlantPro banner" />

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=18&pause=1000&color=6C7A96&center=true&vCenter=true&width=680&lines=ART+inline-hook+base+for+modern+Android;VA-adapted+%2B+Android+16+%2F+API+36+ready;Prebuilt+prefab+static+.a%2C+one+line+to+depend+on" alt="typing" />

<br/>

<img src="https://count.getloli.com/get/@LSPlantPro?theme=moebooru" alt="visitor count" />

<br/>

![License](https://img.shields.io/badge/License-LGPL--3.0-607D8B?style=flat-square)
![Version](https://img.shields.io/badge/lsplant--pro-1.0.0-5C6BC0?style=flat-square)
![Android](https://img.shields.io/badge/Android-16%20%2F%20API%2036-26A69A?style=flat-square)
![Prefab](https://img.shields.io/badge/prefab-static%20.a-78909C?style=flat-square)
![NDK](https://img.shields.io/badge/NDK-29-8B7EC8?style=flat-square)
![Stars](https://img.shields.io/github/stars/Pangu-Immortal/LSPlantPro?style=flat-square&color=607D8B)

**如果对你有帮助,点个 Star ⭐ 支持一下 · If it helps, a Star means a lot**

[简体中文](README.md) | English

</div>

---

## TL;DR

LSPlantPro is the **VA-adapted, prebuilt prefab Maven source** of [LSPosed/LSPlant](https://github.com/LSPosed/LSPlant) (LGPL-3.0, the modern ART method inline-hook base). On top of upstream it ships an **Android 16 / API 36 adaptation**, distributed as "build locally → commit the prefab static-library AAR into the repo → the repo itself is the Maven source" — which works around JitPack being unable to build the C++23 modules native artifacts. One-liner usage: point a maven repo at `raw.githubusercontent`, depend on `com.vapro.lsplant:lsplant-pro:1.0.0`, and statically link on the native side via `find_package(lsplant REQUIRED CONFIG)`. Use it when you need ART method inline-hook capability inside virtualization / app-cloning (VirtualApp-style) engines, Xposed-compatible frameworks, or ART hook engines, with coverage up to the latest Android 16.

## Table of Contents

- [Key Features](#key-features)
- [Quick Start](#quick-start)
- [Artifact Layout](#artifact-layout)
- [Source & Patches (LGPL Compliance)](#source--patches-lgpl-compliance)
- [Rebuild & Publish (Maintainers)](#rebuild--publish-maintainers)
- [License](#license)
- [About the Author](#about-the-author)

## Key Features

| Dimension | Details |
| --- | --- |
| Upstream base | LSPosed/LSPlant, modern ART method inline-hook |
| Adaptation | Android 16 / API 36 (A16/A17 SDK constants, A16 hooker-dex load path, etc.) |
| Distribution | Prebuilt prefab AAR; the repo is the Maven source (raw.githubusercontent) |
| Maven coordinate | `com.vapro.lsplant:lsplant-pro:1.0.0` (`packaging=aar`) |
| Artifact type | Static library only, `liblsplant.a`, self-contained (dex_builder merged in) |
| ABI coverage | `arm64-v8a` / `x86_64` / `armeabi-v7a` / `x86` |
| Build params | `stl=c++_static`, `static=true`, minSdk/api=28, ndk=29 |

## Quick Start

Three steps, copy-paste ready.

### 1. Declare the repository (`settings.gradle.kts`)

```kotlin
dependencyResolutionManagement {
    repositories {
        // LSPlantPro prebuilt prefab Maven source (raw.githubusercontent used directly as a maven repo)
        maven { url = uri("https://raw.githubusercontent.com/Pangu-Immortal/LSPlantPro/main/repo") }
    }
}
```

### 2. Enable prefab and add the dependency in the consuming module (`app/lib build.gradle.kts`)

```kotlin
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("com.vapro.lsplant:lsplant-pro:1.0.0")
}
```

### 3. Consume via `find_package` on the native side (`CMakeLists.txt`)

```cmake
find_package(lsplant REQUIRED CONFIG)
# Statically links into your own .so; lsplant depends on zlib, so link z too
target_link_libraries(your_target lsplant::lsplant z log)
```

`#include <lsplant.hpp>` and you can call `lsplant::Init` / `lsplant::Hook` / `lsplant::IsHooked` and other APIs.

## Artifact Layout

- Ships **static library only**: `prefab/modules/lsplant/libs/android.<abi>/liblsplant.a`, ABI coverage `arm64-v8a / x86_64 / armeabi-v7a / x86`; `stl=c++_static`, `static=true`, minSdk/api=28, ndk=29.
- `liblsplant.a` is a **self-contained** archive: the member objects of upstream `external/dex_builder` (`libdex_builder_static.a`) are merged in, so consumers link a single `.a` to satisfy symbols such as `startop::dex::DexBuilder` (the only extra system library the consumer must link is `z`/zlib).
- **No** `liblsplant.so`: by design it statically links into the host `.so`, avoiding an extra `liblsplant.so` in the host APK.

## Source & Patches (LGPL Compliance)

`src/lsplant-module/` holds the **complete source carrying the VA changes** (including `build.gradle.kts` / `CMakeLists.txt`). See [`PATCHES.md`](./PATCHES.md) for the change points:

- `art/runtime/instrumentation.cxx` — API36 adaptation downgrade (approach 1)
- `lsplant.cc` — A16 `InMemoryDexClassLoader` hooker-dex load path
- `art/runtime/dex_file.cxx` — graceful degradation when `OpenMemory` is missing
- `common.cxx` — SDK constant table extension (A16/A17)

## Rebuild & Publish (Maintainers)

The `build.gradle.kts` under `src/lsplant-module/` already contains the prefab publish and merge configuration. In a Gradle project with Android SDK/NDK 29 + CMake 4.1.2 that includes this module as `:lsplant` in settings:

```bash
# Build the prefab AAR
./gradlew :lsplant:assembleRelease
# Publish to a given maven directory (i.e. this repo's repo/)
./gradlew :lsplant:publishReleasePublicationToLSPlantProLocalRepository \
  -PlsplantProRepo=/abs/path/to/LSPlantPro/repo
```

Key CMake switches (see `src/lsplant-module/src/main/cpp/lsplant/CMakeLists.txt`):

- `-DLSPLANT_BUILD_SHARED=OFF`: static library only, no `liblsplant.so`.
- `-DLSPLANT_PREFAB_MERGE=ON`: names the static target `lsplant` (AGP prefab matches by module name) and merges `dex_builder_static` into `liblsplant.a` as a self-contained archive.
- `-DANDROID_STL=c++_static`: consistent with the host engine STL.

## License

LGPL-3.0 (see [`LICENSE`](./LICENSE)). This repo fully publishes the source including the changes, satisfying LGPL's requirement to "provide the corresponding source for a modified version"; upstream copyright belongs to the LSPosed team.

> LGPL-3.0 **permits commercial use**, with conditions: you must **dynamically link** (or provide relinkable object files) and make the corresponding source of this library (including modifications) available to users. This repo already publishes the full `src/lsplant-module/` source to stay compliant. The upstream license must not be relaxed to a more permissive one.

---

<div align="center">

### Star History

<a href="https://star-history.com/#Pangu-Immortal/LSPlantPro&Date">
  <img src="https://img.shields.io/badge/⭐_Star_History-View_Live_Chart-5C6BC0?style=for-the-badge" alt="Star History"/>
</a>

### About the Author

Primary focus: large-model algorithms / AI / on-device (Agentic · LangGraph · A2A · MCP · ADK · GraphRAG · on-device offline multimodal · automotive · world models).
ROM / reverse engineering is a technical hobby, not a side business.

**Collaboration:** yugu88@126.com · GitHub [@Pangu-Immortal](https://github.com/Pangu-Immortal)

License: LGPL-3.0, commercial use allowed (subject to LGPL dynamic-linking and source-disclosure conditions).

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:3f2b96,100:a8c0ff&height=100&section=footer" alt="footer" />

</div>
