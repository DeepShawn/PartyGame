// 测试辅助：用 esbuild JS API 把纯逻辑 TS 文件临时编译为 mjs 再 import。
import { build } from 'esbuild';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

let cacheDir = null;

async function compile(entry) {
  if (!cacheDir) cacheDir = await mkdtemp(join(tmpdir(), 'partygame-tests-'));
  const source = join(webRoot, 'src', entry);
  const target = join(cacheDir, entry.replace(/[\\/]/g, '_').replace(/\.ts$/, '.mjs'));
  await build({
    entryPoints: [source],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: ['es2020'],
    outfile: target,
    logLevel: 'silent',
  });
  return import(pathToFileURL(target).href);
}

/** 加载 src 下的 TS 源文件（相对 web/src 的路径）。 */
export function loadModule(entry) {
  return compile(entry);
}
