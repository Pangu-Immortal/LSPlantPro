/**
 * ===============================================================================
 * 功能：LSPlant ART Hook 源码库构建配置。
 * 函数简介：以独立 Android library module 形式源码编译 LSPlant，供 SDK 内部聚合和后续
 *           上游升级验证；主引擎 native 链接迁移分阶段完成，避免一次性破坏现有启动链路。
 * ===============================================================================
 */

plugins {
    alias(libs.plugins.android.library)
    // maven-publish：把 prefab AAR 以 com.vapro.lsplant:lsplant-pro:1.0.0 发布到本地 maven 目录，
    //   再拷进 LSPlantPro 仓 repo/ 作为预编译 Maven 源（JitPack 建不了 native，故本地编好提交进仓）。
    `maven-publish`
}

android {
    namespace = "org.lsposed.lsplant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"
    enableKotlin = false

    defaultConfig {
        minSdk = 28
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // 关键逻辑：LSPlantPro 预编译 prefab 发布参数。
                //   LSPLANT_BUILD_SHARED=OFF：只产静态库，不产 liblsplant.so（宿主只需静态入 libvabe.so）。
                //   LSPLANT_PREFAB_MERGE=ON：把 dex_builder_static 合并进 liblsplant_static.a，产出自包含 .a
                //     （消费端拿单个 .a 也能满足 startop::dex::DexBuilder 符号，见 CMakeLists 合并块）。
                //   ANDROID_STL=c++_static：与主引擎 libvabe.so 的 STL 严格一致，避免双 STL/ABI 不匹配。
                arguments += listOf(
                    "-DLSPLANT_BUILD_SHARED=OFF",
                    "-DLSPLANT_PREFAB_MERGE=ON",
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/lsplant/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // prefab 发布：把编好的 liblsplant_static.a（自包含）+ include 头文件打进 AAR 的 prefab/modules/lsplant。
    //   消费端（:lib）用 find_package(lsplant REQUIRED CONFIG) + target_link_libraries(... lsplant::lsplant) 消费。
    buildFeatures {
        prefabPublishing = true
    }
    prefab {
        create("lsplant") {
            // headers：对外公开的 C++ 头（lsplant.hpp 等），消费端 #include <lsplant.hpp>。
            headers = "src/main/cpp/lsplant/include"
            // 无需 libraryName：CMake 静态目标已在 -DLSPLANT_PREFAB_MERGE=ON 下命名为 lsplant，
            //   与本 prefab 模块名一致，AGP 按模块名匹配即可（其产物 liblsplant.a 已合并 dex_builder）。
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        targetSdk = 36
    }

    // maven-publish 前置：声明只发布 release 变体（prefab AAR），AGP 8+ 要求显式 singleVariant。
    publishing {
        singleVariant("release")
    }
}

// 发布配置：坐标 com.vapro.lsplant:lsplant-pro:1.0.0（packaging=aar），发布到 -PlsplantProRepo 指定目录。
//   AGP 的 release 软件组件在 afterEvaluate 后才就绪，故 publishing 块置于 afterEvaluate。
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.vapro.lsplant"
                artifactId = "lsplant-pro"
                version = "1.0.0"
                pom {
                    name.set("LSPlantPro")
                    description.set("LSPlant (VA 适配版) 预编译 prefab 静态库，含 API36 降级等 patch。")
                    licenses {
                        license {
                            name.set("GNU Lesser General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/lgpl-3.0.txt")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "LSPlantProLocal"
                // 目标 maven 目录：通过 -PlsplantProRepo=/abs/path 指定；默认落 build/ 下便于本地验证。
                url = uri(
                    (project.findProperty("lsplantProRepo") as String?)
                        ?: layout.buildDirectory.dir("lsplant-pro-repo").get().asFile.absolutePath
                )
            }
        }
    }
}
