// 共享核心（Kotlin/JS）产物的转发入口。
// scripts/build.mjs 会把 :shared:compileProductionLibraryKotlinJs 的输出
// （shared/build/compileSync/js/main/productionLibrary/kotlin/*）复制到 src/generated/，
// 而 src/core/engine.ts 以相对路径 './generated/partycore.mjs' 引用它（即本文件）。
export * from '../../generated/PartyGame-shared.mjs';
