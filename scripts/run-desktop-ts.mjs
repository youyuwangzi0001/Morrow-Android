/**
 * 以「可加载桌面端 .ts 源码」的方式运行一个校验脚本。
 *
 * 用法：`node scripts/run-desktop-ts.mjs scripts/gen-secure-vectors.mjs --check`
 *
 * 为什么不直接在 verify.sh 里写 `node --import hooks.mjs /abs/path/script.mjs`：
 * verify.sh 由 git-bash 执行，`$ROOT` 是 `/c/...` 形式的 POSIX 路径，交给 Windows
 * 版 Node 后会变成 `c:/...`，被 ESM 加载器当成协议名为 `c:` 的 URL 而报
 * ERR_UNSUPPORTED_ESM_URL_SCHEME。这里统一用 pathToFileURL 转成 file: URL，
 * 参数怎么传都不会再踩到路径形式的问题。
 */
import { register } from 'node:module'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

register(new URL('./ts-hooks.mjs', import.meta.url))

const [target, ...rest] = process.argv.slice(2)
if (!target) {
  console.error('用法：node scripts/run-desktop-ts.mjs <脚本路径> [参数...]')
  process.exit(2)
}

const entry = pathToFileURL(resolve(target)).href
// 保留原始参数形态：脚本里有读 process.argv.includes('--check') 的分支。
process.argv = [process.argv[0], target, ...rest]
await import(entry)
