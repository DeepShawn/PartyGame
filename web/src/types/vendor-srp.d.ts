/**
 * src/security/vendor 下的 thinbus-srp 副本以 .mjs 发布且不带类型声明；
 * 调用处已有显式断言，这里只补环境模块声明，避免 TS7016。
 */
declare module '*/vendor/thinbus-client.mjs';
declare module '*/vendor/thinbus-server.mjs';
