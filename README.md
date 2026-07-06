# LSPlantPro

LSPlant（**VA 适配版**）的**预编译 prefab Maven 源**。

- 上游：[LSPosed/LSPlant](https://github.com/LSPosed/LSPlant)（LGPL-3.0），现代 ART 方法 inline-hook 底座。
- 本仓在上游基础上做了 **Android 16 / API 36 适配** 等改动（见 [`PATCHES.md`](./PATCHES.md)），并以
  **预编译 prefab 静态库 AAR** 形式发布：因 JitPack 无法构建 C++23 modules 的 native 产物，
  这里采用「**本地编好、把 AAR 提交进仓、仓库本身即 Maven 源**」的方式分发。
- 坐标：`com.vapro.lsplant:lsplant-pro:1.0.0`（`packaging=aar`）。

## 一、依赖使用（消费端）

### 1. 声明仓库（`settings.gradle.kts`）

```kotlin
dependencyResolutionManagement {
    repositories {
        // LSPlantPro 预编译 prefab Maven 源（raw.githubusercontent 直接当 maven repo）
        maven { url = uri("https://raw.githubusercontent.com/Pangu-Immortal/LSPlantPro/main/repo") }
    }
}
```

### 2. 消费模块启用 prefab 并加依赖（`app/lib build.gradle.kts`）

```kotlin
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("com.vapro.lsplant:lsplant-pro:1.0.0")
}
```

### 3. native 侧用 `find_package` 消费（`CMakeLists.txt`）

```cmake
find_package(lsplant REQUIRED CONFIG)
# 静态链接进你自己的 .so；lsplant 依赖 zlib，需一并链接 z
target_link_libraries(your_target lsplant::lsplant z log)
```

`#include <lsplant.hpp>` 即可调用 `lsplant::Init` / `lsplant::Hook` / `lsplant::IsHooked` 等 API。

## 二、产物形态

- 只发布**静态库**：`prefab/modules/lsplant/libs/android.<abi>/liblsplant.a`，ABI 覆盖
  `arm64-v8a / x86_64 / armeabi-v7a / x86`；`stl=c++_static`，`static=true`，minSdk/api=28，ndk=29。
- `liblsplant.a` 为**自包含**归档：已把上游 `external/dex_builder`（`libdex_builder_static.a`）
  的成员对象合并进来，消费端只链接单个 `.a` 即可满足 `startop::dex::DexBuilder` 等符号
  （唯一需消费端额外链接的系统库是 `z`/zlib）。
- **不含** `liblsplant.so`：设计上静态入宿主 `.so`，避免宿主 APK 多打一份 `liblsplant.so`。

## 三、源码（LGPL 合规）

`src/lsplant-module/` 为**携带 VA 改动的完整源码**（含 `build.gradle.kts` / `CMakeLists.txt`）。
改动点见 [`PATCHES.md`](./PATCHES.md)：

- `art/runtime/instrumentation.cxx` —— API36 适配降级（方案1）
- `lsplant.cc` —— A16 `InMemoryDexClassLoader` hooker-dex 加载路径
- `art/runtime/dex_file.cxx` —— `OpenMemory` 缺失优雅降级
- `common.cxx` —— SDK 常量表扩展（A16/A17）

## 四、本地重新编译 / 发布（维护者）

本仓 `src/lsplant-module/` 的 `build.gradle.kts` 已内置 prefab 发布与合并配置。在一个包含
Android SDK/NDK 29 + CMake 4.1.2、且把本模块以 `:lsplant` 纳入 settings 的 Gradle 工程里：

```bash
# 编出 prefab AAR
./gradlew :lsplant:assembleRelease
# 发布到指定 maven 目录（即本仓 repo/）
./gradlew :lsplant:publishReleasePublicationToLSPlantProLocalRepository \
  -PlsplantProRepo=/abs/path/to/LSPlantPro/repo
```

关键 CMake 开关（见 `src/lsplant-module/src/main/cpp/lsplant/CMakeLists.txt`）：

- `-DLSPLANT_BUILD_SHARED=OFF`：只产静态库，不产 `liblsplant.so`。
- `-DLSPLANT_PREFAB_MERGE=ON`：静态目标命名为 `lsplant`（AGP prefab 按模块名匹配），
  并把 `dex_builder_static` 合并进 `liblsplant.a` 成自包含归档。
- `-DANDROID_STL=c++_static`：与宿主引擎 STL 一致。

## 五、许可证

LGPL-3.0（见 [`LICENSE`](./LICENSE)）。本仓完整公开含改动的源码，满足 LGPL 对
「发布修改版须提供对应源码」的要求。上游版权归 LSPosed 团队所有。
