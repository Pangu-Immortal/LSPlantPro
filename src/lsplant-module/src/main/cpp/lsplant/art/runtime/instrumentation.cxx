module;

#include "logging.hpp"

export module lsplant:instrumentation;

import :art_method;
import :common;
import hook_helper;

namespace lsplant::art {

export class Instrumentation {
    inline static ArtMethod *MaybeUseBackupMethod(ArtMethod *art_method, const void *quick_code) {
        if (auto backup = IsHooked(art_method); backup && art_method->GetEntryPoint() != quick_code)
            [[unlikely]] {
            LOGD("Propagate update method code %p for hooked method %p to its backup", quick_code,
                 art_method);
            return backup;
        }
        return art_method;
    }

    inline static auto UpdateMethodsCodeToInterpreterEntryPoint_ =
        "_ZN3art15instrumentation15Instrumentation40UpdateMethodsCodeToInterpreterEntryPointEPNS_9ArtMethodE"_sym.hook->*[]
        <MemBackup auto backup>
        (Instrumentation *thiz, ArtMethod *art_method) static -> void {
            if (IsDeoptimized(art_method)) {
                LOGV("skip update entrypoint on deoptimized method %s",
                     art_method->PrettyMethod(true).c_str());
                return;
            }
            backup(thiz, MaybeUseBackupMethod(art_method, nullptr));
        };

    inline static auto InitializeMethodsCode_ =
        "_ZN3art15instrumentation15Instrumentation21InitializeMethodsCodeEPNS_9ArtMethodEPKv"_sym.hook->*[]
         <MemBackup auto backup>
         (Instrumentation *thiz, ArtMethod *art_method, const void *quick_code) static -> void {
            if (IsDeoptimized(art_method)) {
                LOGV("skip update entrypoint on deoptimized method %s",
                     art_method->PrettyMethod(true).c_str());
                return;
            }
            backup(thiz, MaybeUseBackupMethod(art_method, quick_code), quick_code);
        };

    inline static auto ReinitializeMethodsCode_ =
        "_ZN3art15instrumentation15Instrumentation23ReinitializeMethodsCodeEPNS_9ArtMethodE"_sym.hook->*[]
         <MemBackup auto backup>
         (Instrumentation *thiz, ArtMethod *art_method) static -> void {
            if (IsDeoptimized(art_method)) {
                LOGV("skip update entrypoint on deoptimized method %s",
                     art_method->PrettyMethod(true).c_str());
                return;
            }
            backup(thiz, MaybeUseBackupMethod(art_method, nullptr));
        };

public:
    static bool Init(JNIEnv *env, const HookHandler &handler) {
        if (!IsJavaDebuggable(env)) [[likely]] {
            return true;
        }
        int sdk_int = GetAndroidApiLevel();
        if (sdk_int >= kSdkPie) [[likely]] {
            // API36 适配降级（patch 方案1）：部分 ROM 的 InitializeMethodsCode /
            //   ReinitializeMethodsCode / UpdateMethodsCodeToInterpreterEntryPoint 等 inline 符号
            //   缺失或不可 hook，handler 会因 inline hook 失败而返回 false。此处降级为忽略失败：
            //   不再 return false，保证 Instrumentation::Init 继续返回 true，不阻断 LSPlant 初始化。
            //   影响：被 hook 方法的 entrypoint 不会被同步更新到 backup 方法，但不影响
            //   lsplant::Hook 主链路（Java 方法 Hook 走 dex_builder stub class，不依赖此 inline hook）。
            //   x86 ABI 下 MSHookFunction 为桩时同样走此降级路径。
            handler(ReinitializeMethodsCode_, InitializeMethodsCode_, UpdateMethodsCodeToInterpreterEntryPoint_);
        }
        return true;
    }
};

}  // namespace lsplant::art
