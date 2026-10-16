import { build } from 'esbuild';
import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = resolve(webRoot, '..');
const dist = join(webRoot, 'dist');
const generated = join(webRoot, 'src', 'generated');
const androidAssets = join(repoRoot, 'app', 'src', 'main', 'assets', 'web');

async function stageSharedCore() {
  const candidates = [
    join(repoRoot, 'shared', 'build', 'compileSync', 'js', 'main', 'productionLibrary', 'kotlin'),
    join(repoRoot, 'shared', 'build', 'compileSync', 'js', 'productionLibrary', 'kotlin'),
    join(repoRoot, 'shared', 'build', 'dist', 'js', 'productionLibrary'),
  ];
  let source = null;
  for (const dir of candidates) {
    if (existsSync(join(dir, 'PartyGame-shared.mjs'))) { source = dir; break; }
  }
  if (!source) {
    throw new Error('未找到 Kotlin/JS 共享核心产物，请先运行: ./gradlew :shared:compileProductionLibraryKotlinJs');
  }
  await rm(generated, { recursive: true, force: true });
  await mkdir(generated, { recursive: true });
  await cp(source, generated, { recursive: true });
  console.log(`shared core: ${source} -> ${generated}`);
}

async function copyIcons() {
  const icons = join(webRoot, 'public', 'icons');
  if (existsSync(icons)) {
    await cp(icons, join(dist, 'icons'), { recursive: true });
    console.log('icons copied');
  } else {
    console.warn('warn: web/public/icons 缺失，将使用占位图标');
  }
}

async function copyStockfish() {
  const dirs = [
    join(webRoot, 'node_modules', 'stockfish', 'bin'),
    join(webRoot, 'node_modules', 'stockfish'),
  ];
  for (const dir of dirs) {
    if (!existsSync(dir)) continue;
    for (const name of await readdir(dir)) {
      if (/^stockfish-19-lite-single\.(js|wasm)$/.test(name)) {
        await cp(join(dir, name), join(dist, name));
        console.log(`engine asset: ${name}`);
      }
    }
  }
}

async function copyDesignTokens() {
  const tokens = join(repoRoot, 'design', 'tokens.json');
  if (existsSync(tokens)) {
    await cp(tokens, join(dist, 'tokens.json'));
  }
}

async function writeManifest() {
  const tokensPath = join(repoRoot, 'design', 'tokens.json');
  let themeColor = '#0f6f6a';
  if (existsSync(tokensPath)) {
    try {
      const tokens = JSON.parse(await readFile(tokensPath, 'utf8'));
      themeColor = tokens?.themes?.[0]?.accent ?? themeColor;
    } catch { /* 使用默认色 */ }
  }
  const icons = [];
  for (const size of [192, 512]) {
    for (const type of ['png']) {
      const candidate = join(webRoot, 'public', 'icons', `trio-${size}.${type}`);
      if (existsSync(candidate)) {
        icons.push({ src: `icons/trio-${size}.${type}`, sizes: `${size}x${size}`, type: 'image/png' });
      }
    }
  }
  if (icons.length === 0) {
    icons.push({ src: 'icons/trio.svg', sizes: 'any', type: 'image/svg+xml' });
  }
  const manifest = {
    name: '同乐盒 PartyGame',
    short_name: '同乐盒',
    description: '16 款 2～3 人小游戏与棋牌，局域网/公网联机，口令房间，离线 AI。',
    start_url: './',
    scope: './',
    display: 'standalone',
    background_color: '#101418',
    theme_color: themeColor,
    lang: 'zh-CN',
    icons,
  };
  await writeFile(join(dist, 'manifest.webmanifest'), JSON.stringify(manifest, null, 2));
}

async function main() {
  await stageSharedCore();
  await rm(dist, { recursive: true, force: true });
  await mkdir(join(dist, 'assets'), { recursive: true });

  await build({
    entryPoints: [join(webRoot, 'src', 'main.tsx')],
    bundle: true,
    format: 'esm',
    target: ['es2020'],
    platform: 'browser',
    minify: !process.argv.includes('--dev'),
    sourcemap: process.argv.includes('--dev') ? 'inline' : false,
    outfile: join(dist, 'assets', 'app.js'),
    logLevel: 'info',
    loader: { '.css': 'css' },
  });

  await cp(join(webRoot, 'index.html'), join(dist, 'index.html'));
  if (existsSync(join(webRoot, 'admin.html'))) await cp(join(webRoot, 'admin.html'), join(dist, 'admin.html'));
  await writeFile(join(dist, '.nojekyll'), '');
  await copyIcons();
  await copyStockfish();
  await copyDesignTokens();
  await writeManifest();

  if (existsSync(androidAssets)) await rm(androidAssets, { recursive: true, force: true });
  await cp(dist, androidAssets, { recursive: true });
  console.log(`android assets: ${androidAssets}`);

  const size = await directorySize(dist);
  console.log(`web build ok: ${dist} (${(size / 1024 / 1024).toFixed(2)} MiB)`);
}

async function directorySize(dir) {
  let total = 0;
  for (const name of await readdir(dir)) {
    const info = await stat(join(dir, name));
    total += info.isDirectory() ? await directorySize(join(dir, name)) : info.size;
  }
  return total;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
