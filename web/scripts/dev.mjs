// 本地开发服务器：esbuild watch + 静态托管 dist；共享核心产物缺失时给出明确提示。
import { context } from 'esbuild';
import { createServer } from 'node:http';
import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = resolve(fileURLToPath(new URL('.', import.meta.url)), '..');
const repoRoot = resolve(webRoot, '..');
const dist = join(webRoot, 'dist');
const port = Number(process.env.PORT ?? 5273);

async function firstFile(dir, suffix) {
  if (!existsSync(dir)) return null;
  for (const name of await readdir(dir)) if (name.endsWith(suffix)) return join(dir, name);
  return null;
}

async function stageSharedCore() {
  const candidates = [
    join(repoRoot, 'shared', 'build', 'dist', 'js', 'productionLibrary'),
    join(repoRoot, 'shared', 'build', 'dist', 'js', 'productionExecutable'),
    join(repoRoot, 'shared', 'build', 'compileSync', 'js', 'productionLibrary'),
  ];
  for (const dir of candidates) {
    const file = await firstFile(dir, '.mjs');
    if (!file) continue;
    await mkdir(join(webRoot, 'src', 'generated'), { recursive: true });
    await cp(file, join(webRoot, 'src', 'generated', 'partycore.mjs'));
    console.log(`shared core: ${file}`);
    return true;
  }
  console.warn('警告：未找到共享核心产物，请先运行 ./gradlew :shared:jsProductionLibrary');
  return false;
}

async function stageStatics() {
  await mkdir(join(dist, 'assets'), { recursive: true });
  await cp(join(webRoot, 'index.html'), join(dist, 'index.html'));
  await writeFile(join(dist, '.nojekyll'), '');
  const icons = join(webRoot, 'public', 'icons');
  if (existsSync(icons)) await cp(icons, join(dist, 'icons'), { recursive: true });
  const tokens = join(repoRoot, 'design', 'tokens.json');
  if (existsSync(tokens)) await cp(tokens, join(dist, 'tokens.json'));
  for (const dir of [join(webRoot, 'node_modules', 'stockfish', 'bin'), join(webRoot, 'node_modules', 'stockfish')]) {
    if (!existsSync(dir)) continue;
    for (const name of await readdir(dir)) {
      if (/^stockfish-19-lite-single\.(js|wasm)$/.test(name)) await cp(join(dir, name), join(dist, name));
    }
  }
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
};

function serve() {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);
    const relative = url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname).replace(/^\/+/, '');
    const target = normalize(join(dist, relative));
    if (!target.startsWith(dist)) {
      response.writeHead(403).end('forbidden');
      return;
    }
    try {
      const info = await stat(target);
      const file = info.isDirectory() ? join(target, 'index.html') : target;
      const body = await readFile(file);
      response.writeHead(200, {
        'content-type': MIME[extname(file)] ?? 'application/octet-stream',
        'cache-control': 'no-store',
      });
      response.end(body);
    } catch {
      response.writeHead(404).end('not found');
    }
  });
  server.listen(port, '0.0.0.0', () => {
    console.log(`dev server: http://localhost:${port}/`);
  });
  return server;
}

async function main() {
  await rm(dist, { recursive: true, force: true });
  const staged = await stageSharedCore();
  await stageStatics();
  let watcher = null;
  try {
    watcher = await context({
      entryPoints: [join(webRoot, 'src', 'main.tsx')],
      bundle: true,
      format: 'esm',
      target: ['es2020'],
      platform: 'browser',
      sourcemap: 'inline',
      outfile: join(dist, 'assets', 'app.js'),
      loader: { '.css': 'css' },
      logLevel: 'info',
    });
    await watcher.watch();
  } catch (error) {
    console.error(`构建失败：${error instanceof Error ? error.message : error}`);
    if (!staged) console.error('提示：先运行 ./gradlew :shared:jsProductionLibrary 生成共享核心，再重新启动本命令。');
  }
  const server = serve();
  const shutdown = async () => {
    await watcher?.dispose();
    server.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
