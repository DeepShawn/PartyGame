#!/usr/bin/env bash
# 构建 PartyGame 发布产物：共享规则(JS) -> 网页 -> 签名 APK。
# 依赖：JDK 17、Android SDK、Node 18+、web/node_modules 已安装。
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk-arm64}"
export JAVA_HOME

echo "== 1/4 共享规则编译（JVM 测试 + JS 产物）"
./gradlew :shared:jvmTest :shared:compileProductionLibraryKotlinJs --no-daemon --console=plain

echo "== 2/4 网页构建（含安卓内置 web assets）"
(cd web && node scripts/build.mjs)

echo "== 3/4 Android 单元测试 + 签名 Release APK"
./gradlew :app:testDebugUnitTest :app:assembleRelease --no-daemon --console=plain

APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK" || { echo "未找到 $APK"; exit 1; }

echo "== 4/4 产物校验值"
sha256sum "$APK" | tee app/build/outputs/apk/release/app-release.apk.sha256
ls -lh "$APK"
