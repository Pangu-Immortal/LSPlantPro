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

简体中文 | [English](README_EN.md)

</div>

---

## TL;DR

LSPlantPro 是 [LSPosed/LSPlant](https://github.com/LSPosed/LSPlant)(LGPL-3.0,现代 ART 方法 inline-hook 底座)的 **VA 适配版预编译 prefab Maven 源**。它在上游基础上完成了 **Android 16 / API 36 适配**,并以「本地编好 → 把 prefab 静态库 AAR 提交进仓库 → 仓库本身即 Maven 源」的方式分发,解决 JitPack 无法构建 C++23 modules native 产物的问题。一句话用法:在 `settings.gradle.kts` 里把 `raw.githubusercontent` 当作 maven repo,依赖 `com.vapro.lsplant:lsplant-pro:1.0.0`,native 侧用 `find_package(lsplant REQUIRED CONFIG)` 即可静态链接。适用场景:需要在虚拟化/多开(VirtualApp 类)、Xposed 兼容框架、ART Hook 引擎中集成方法 inline-hook 能力,并要覆盖 Android 16 最新系统的 Android C++ 项目。

## 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
- [产物形态](#产物形态)
- [源码与改动(LGPL 合规)](#源码与改动lgpl-合规)
- [本地重新编译--发布维护者](#本地重新编译--发布维护者)
- [许可证](#许可证)
- [关于作者](#关于作者)

## 核心特性

| 维度 | 说明 |
| --- | --- |
| 上游底座 | LSPosed/LSPlant,现代 ART 方法 inline-hook |
| 适配范围 | Android 16 / API 36(A16/A17 SDK 常量、A16 hooker-dex 加载路径等) |
| 分发方式 | 预编译 prefab AAR,仓库即 Maven 源(raw.githubusercontent) |
| Maven 坐标 | `com.vapro.lsplant:lsplant-pro:1.0.0`(`packaging=aar`) |
| 产物类型 | 仅静态库 `liblsplant.a`,自包含(已合并 dex_builder) |
| ABI 覆盖 | `arm64-v8a` / `x86_64` / `armeabi-v7a` / `x86` |
| 构建参数 | `stl=c++_static`、`static=true`、minSdk/api=28、ndk=29 |

## 快速开始

三步接入,直接复制即可照做。

### 1. 声明仓库(`settings.gradle.kts`)

```kotlin
dependencyResolutionManagement {
    repositories {
        // LSPlantPro 预编译 prefab Maven 源(raw.githubusercontent 直接当 maven repo)
        maven { url = uri("https://raw.githubusercontent.com/Pangu-Immortal/LSPlantPro/main/repo") }
    }
}
```

### 2. 消费模块启用 prefab 并加依赖(`app/lib build.gradle.kts`)

```kotlin
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("com.vapro.lsplant:lsplant-pro:1.0.0")
}
```

### 3. native 侧用 `find_package` 消费(`CMakeLists.txt`)

```cmake
find_package(lsplant REQUIRED CONFIG)
# 静态链接进你自己的 .so;lsplant 依赖 zlib,需一并链接 z
target_link_libraries(your_target lsplant::lsplant z log)
```

`#include <lsplant.hpp>` 即可调用 `lsplant::Init` / `lsplant::Hook` / `lsplant::IsHooked` 等 API。

## 产物形态

- 只发布**静态库**:`prefab/modules/lsplant/libs/android.<abi>/liblsplant.a`,ABI 覆盖 `arm64-v8a / x86_64 / armeabi-v7a / x86`;`stl=c++_static`、`static=true`、minSdk/api=28、ndk=29。
- `liblsplant.a` 为**自包含**归档:已把上游 `external/dex_builder`(`libdex_builder_static.a`)的成员对象合并进来,消费端只链接单个 `.a` 即可满足 `startop::dex::DexBuilder` 等符号(唯一需消费端额外链接的系统库是 `z`/zlib)。
- **不含** `liblsplant.so`:设计上静态入宿主 `.so`,避免宿主 APK 多打一份 `liblsplant.so`。

## 源码与改动(LGPL 合规)

`src/lsplant-module/` 为**携带 VA 改动的完整源码**(含 `build.gradle.kts` / `CMakeLists.txt`)。改动点见 [`PATCHES.md`](./PATCHES.md):

- `art/runtime/instrumentation.cxx` —— API36 适配降级(方案1)
- `lsplant.cc` —— A16 `InMemoryDexClassLoader` hooker-dex 加载路径
- `art/runtime/dex_file.cxx` —— `OpenMemory` 缺失优雅降级
- `common.cxx` —— SDK 常量表扩展(A16/A17)

## 本地重新编译--发布(维护者)

本仓 `src/lsplant-module/` 的 `build.gradle.kts` 已内置 prefab 发布与合并配置。在一个包含 Android SDK/NDK 29 + CMake 4.1.2、且把本模块以 `:lsplant` 纳入 settings 的 Gradle 工程里:

```bash
# 编出 prefab AAR
./gradlew :lsplant:assembleRelease
# 发布到指定 maven 目录(即本仓 repo/)
./gradlew :lsplant:publishReleasePublicationToLSPlantProLocalRepository \
  -PlsplantProRepo=/abs/path/to/LSPlantPro/repo
```

关键 CMake 开关(见 `src/lsplant-module/src/main/cpp/lsplant/CMakeLists.txt`):

- `-DLSPLANT_BUILD_SHARED=OFF`:只产静态库,不产 `liblsplant.so`。
- `-DLSPLANT_PREFAB_MERGE=ON`:静态目标命名为 `lsplant`(AGP prefab 按模块名匹配),并把 `dex_builder_static` 合并进 `liblsplant.a` 成自包含归档。
- `-DANDROID_STL=c++_static`:与宿主引擎 STL 一致。

## 许可证

LGPL-3.0(见 [`LICENSE`](./LICENSE))。本仓完整公开含改动的源码,满足 LGPL 对「发布修改版须提供对应源码」的要求;上游版权归 LSPosed 团队所有。

> LGPL-3.0 下**允许商用**,但有条件:必须**动态链接**或提供可重新链接的目标文件,并向使用者公开本库(含改动)的对应源码;本仓已完整公开 `src/lsplant-module/` 源码以满足合规。上游许可证不得改成更宽松协议。

---

<div align="center">

### Star History

<a href="https://star-history.com/#Pangu-Immortal/LSPlantPro&Date">
  <img src="https://img.shields.io/badge/⭐_Star_History-View_Live_Chart-5C6BC0?style=for-the-badge" alt="Star History"/>
</a>

### 关于作者

主业:大模型算法 / AI / 端侧(Agentic · LangGraph · A2A · MCP · ADK · GraphRAG · 端侧离线多模态 · 车载 · 世界模型)。
ROM / 逆向为技术爱好,非副业。

**合作交流 · Collaboration:** yugu88@126.com · GitHub [@Pangu-Immortal](https://github.com/Pangu-Immortal)

License: LGPL-3.0,允许商用(需遵守 LGPL 动态链接与源码公开条件)。

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:3f2b96,100:a8c0ff&height=100&section=footer" alt="footer" />

</div>
