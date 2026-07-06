# PATCHES.md — VA 适配版相对上游 LSPlant 的改动清单

- 上游（Upstream）：https://github.com/LSPosed/LSPlant （LGPL-3.0）
- 打补丁的源码根目录：`src/lsplant-module/src/main/cpp/lsplant/`
- 许可证：LGPL-3.0（本仓完整公开含改动的源码，满足 LGPL 对“修改版须提供对应源码”的要求）

## 摘要：携带 VA 改动的文件（4 个）

| 文件 | 改动主题 |
| --- | --- |
| `art/runtime/instrumentation.cxx` | **API36 适配降级（方案1）**：部分 ROM / x86 上 `InitializeMethodsCode` 等 inline 符号缺失或不可 hook 时，忽略失败、不再让 `Instrumentation::Init` 返回 false，保证 LSPlant 初始化不被阻断。 |
| `lsplant.cc` | **A16 hooker-dex 加载重写**：新增 `InMemoryDexClassLoader` 路径，规避 Android 16 / API 36 上 `DexFile(ByteBuffer)` 构造被移除、`art::DexFile::OpenMemory` 符号缺失导致 hooker 类无法加载的问题。 |
| `art/runtime/dex_file.cxx` | 软化 `DexFile::Init` 与 `ToJavaDexFile`：`OpenMemory` 缺失或 Java `DexFile` 字段未初始化时（A16 情形）在 Oreo+ 优雅降级（`LOGW`+continue）而非硬失败，不回归已验证的 API30-35 路径。 |
| `common.cxx` | SDK 常量表扩展：新增 `kSdkBaklava = 36`（A16）、`kSdkCinnamonBun = 37`（A17），供上述补丁做版本门控。 |

`external/dex_builder/`（第三方 vendored 库）经核对未被 VA 改动。

---

## `art/runtime/instrumentation.cxx` —— API36 适配降级（方案1，旗舰补丁）

**改动与原因。** 上游 `Instrumentation::Init` 直接 `return handler(...)`：一旦三个 inline 符号
（`InitializeMethodsCode` / `ReinitializeMethodsCode` / `UpdateMethodsCodeToInterpreterEntryPoint`）
缺失或无法 inline hook，`handler` 返回 false，整个 LSPlant 初始化中止。部分 A16 ROM 及 x86
（本构建中 `MSHookFunction` 为桩）上这些符号缺失/不可 hook。补丁改为：**调用 `handler(...)`
但忽略其返回值，恒 `return true`**，让初始化继续。代价（注释已载明）：被 hook 方法的入口点
可能不会传播到 backup，但主 `lsplant::Hook` 路径（走 `dex_builder` 桩类）不依赖此 inline hook，
因此 hook 仍可用。

**触及区域。** `Instrumentation::Init(JNIEnv*, const HookHandler&)`，约 61–77 行。

```cpp
if (sdk_int >= kSdkPie) [[likely]] {
    // API36 适配降级（patch 方案1）：部分 ROM 的 InitializeMethodsCode /
    //   ReinitializeMethodsCode / UpdateMethodsCodeToInterpreterEntryPoint 等 inline 符号
    //   缺失或不可 hook，handler 会因 inline hook 失败而返回 false。此处降级为忽略失败：
    //   不再 return false，保证 Instrumentation::Init 继续返回 true，不阻断 LSPlant 初始化。
    //   x86 ABI 下 MSHookFunction 为桩时同样走此降级路径。
    handler(ReinitializeMethodsCode_, InitializeMethodsCode_, UpdateMethodsCodeToInterpreterEntryPoint_);
}
return true;
```
（上游等价写法为 `if (...) return handler(...);`。）

---

## `art/runtime/dex_file.cxx` —— OpenMemory 缺失优雅降级

**改动与原因。** 两处相关软化，使 A16 上 `OpenMemory` 符号缺失不再中止初始化，同时不回归
已验证的 API 30–35 路径：

1. **`DexFile::Init`** —— 上游在找不到 `OpenMemory`、或 `dalvik/system/DexFile` 类 / 其
   `mCookie` / `mFileName` / `mInternalCookie` 字段缺失时硬失败（`return false`）。补丁把每一处改为
   **Oreo+ 优雅继续**（`LOGW` + `return true`，或不带 internal cookie 继续）：Android 8–15 上
   hook 正常走 `DexFile(ByteBuffer)` 构造，`OpenMemory` 只是兜底；仅 pre-Oreo 仍硬失败。
2. **`ToJavaDexFile`** —— 新增守卫：若 Java `DexFile` 字段从未初始化（A16 情形），返回 `nullptr`，
   让调用方走 hook 失败降级路径，而非写入空 `jfieldID`。

**触及区域。** `ToJavaDexFile(JNIEnv*)` 守卫约 67–73 行；`DexFile::Init(JNIEnv*, const HookHandler&)`
约 99–158 行（`OpenMemory` handler 检查与四处字段查找改为 `sdk_int >= kSdkOreo` 分支）。

```cpp
if (!handler(OpenMemory_, OpenMemoryRaw_, OpenMemoryWithoutOdex_)) [[unlikely]] {
    if (sdk_int >= kSdkOreo) {
        // 关键逻辑：Android 8-15 通常走 DexFile(ByteBuffer) 构造，OpenMemory 只是 API36 兜底。
        // 不在初始化阶段失败，避免破坏已验证过的 API30-35 路径。
        LOGW("OpenMemory not found, ByteBuffer DexFile path may still work on this SDK.");
    } else {
        LOGE("Failed to find OpenMemory");
        return false;
    }
}
```

---

## `lsplant.cc` —— A16 hooker-dex 加载重写（“A16 分身无法启动”根因修复）

**改动与原因。** Android 16 / API 36 上，`DexFile(ByteBuffer)` 构造被移除，且 `libart` 不再导出
`art::DexFile::OpenMemory`（换成 `DexFileLoader::Open`，签名 `vector<unique_ptr<DexFile>>` 不兼容），
导致 LSPlant 无法加载自生成的 hooker dex → hook backup 为空 → 全部 VA Java 方法 hook 失效 →
分身崩溃 / ANR。补丁引入纯 Java 加载路径 `dalvik.system.InMemoryDexClassLoader`
（API 26+ `@SystemApi`，JNI `FindClass` 可达、不被 hidden-api 拦），不依赖任何 art native 符号。
三处协同改动：

1. **新增 JNI 方法句柄**（约 117–124 行）：`in_memory_dex_cl_init`
   （`InMemoryDexClassLoader.<init>(ByteBuffer, ClassLoader)`）、`class_loader_load_class`
   （`ClassLoader.loadClass(String)`）。故意**不**加入 `kInternalMethods`，使旧设备缺失时不致初始化失败。
2. **`InitJNI`**（约 246–278 行）：Oreo+ 解析两个新句柄；`DexFile(ByteBuffer)` 构造缺失（A16）时
   仅告警不失败，交给 native `OpenMemory` 兜底；Baklava+ 上若 `InMemoryDexClassLoader.<init>` 也缺失才告警
   （最后的纯 Java 兜底）。
3. **`CreateHookerClass`**（约 457–522 行）：新加载优先级 —— `DexFile(ByteBuffer)` 构造（旧路径）
   → **`InMemoryDexClassLoader`**（A16 主路径）→ native `OpenMemory` mmap 兜底（legacy）。
   走 `InMemoryDexClassLoader` 成功时，hooker 类经 `ClassLoader.loadClass(String)` 加载。

**触及区域。** 全局句柄声明约 117–124；`InitJNI` 约 246–278；`CreateHookerClass` 约 457–522。

```cpp
// VA 修复（A16 根因）：DexFile(ByteBuffer) 移除时，优先用 InMemoryDexClassLoader
if (in_memory_dex_cl_init && class_loader_load_class) {
    auto imcl_class = JNI_FindClass(env, "dalvik/system/InMemoryDexClassLoader");
    if (imcl_class) {
        auto bb = JNI_NewDirectByteBuffer(env, const_cast<void *>(image.ptr()), image.size());
        hooker_cl = JNI_NewObject(env, imcl_class, in_memory_dex_cl_init, bb, class_loader);
    }
    // 异常清理 …
}
if (!hooker_cl) {
    // 最终 fallback：native OpenMemory（A16 miss，旧设备/OpenMemory 可用时走此路径）。
    // … mmap + memcpy + DexFile::OpenMemory + ToJavaDexFile …
}
```

---

## `common.cxx` —— SDK 常量表扩展 A16/A17

```cpp
constexpr int kSdkVanillaIceCream = 35;
constexpr int kSdkBaklava = 36;        // Android 16 / API 36 — 被 lsplant.cc 的 A16 门控引用
constexpr int kSdkCinnamonBun = 37;    // Android 17（前瞻，定义但暂未引用）
```

`kSdkBaklava`(36) 被 `lsplant.cc` 的 A16 门控（`sdk_int >= kSdkBaklava`）消费。
