/**
 * 模块加载钩子（由 ts-resolve-hooks.mjs 注册）。
 *
 * 桌面源码直接以 `.ts` 被 Node 加载时需要三件事，都在这里处理：
 *
 * 1. 相对 import 不带扩展名（`./logger`），Node 不做推断 —— 补 `.ts`。
 * 2. `@core` / `@shared` 是构建期别名 —— 映射到 `Morrow/src` 下对应目录。
 * 3. Node 的 strip-types 只删类型不做降级，遇到构造函数参数属性直接报错，
 *    而桌面源码大量使用 —— 改用 esbuild 转译，与实际构建同一套转译器。
 *
 * `electron` 由 electron-stub.ts 顶替：只提供导入期需要的形状，真调用即抛错。
 */
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const esbuild = require('../../Morrow/node_modules/esbuild')
const stubUrl = new URL('./electron-stub.ts', import.meta.url).href
const desktopRoot = new URL('../../Morrow/src/', import.meta.url).href

export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'electron') return { url: stubUrl, shortCircuit: true }
  if (specifier.startsWith('@core/') || specifier.startsWith('@shared/')) {
    const [alias, ...rest] = specifier.split('/')
    const dir = alias === '@core' ? 'core/' : 'shared/'
    return { url: new URL(`${dir}${rest.join('/')}.ts`, desktopRoot).href, shortCircuit: true }
  }
  if (specifier.startsWith('.') && !specifier.endsWith('.ts')) {
    try {
      return await nextResolve(`${specifier}.ts`, context)
    } catch {
      // 需要方原本要的可能是目录或包，交给默认解析
    }
  }
  return nextResolve(specifier, context)
}

export async function load(url, context, nextLoad) {
  if (url.startsWith('file:') && url.endsWith('.ts')) {
    const source = readFileSync(new URL(url), 'utf8')
    const { code } = esbuild.transformSync(source, { loader: 'ts', format: 'esm', target: 'node22' })
    return { format: 'module', source: code, shortCircuit: true }
  }
  return nextLoad(url, context)
}
