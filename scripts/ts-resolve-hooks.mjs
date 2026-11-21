/**
 * 让向量校验脚本能直接加载桌面端 `.ts` 源码的注册入口。
 *
 * 用法：`node --import ./scripts/ts-resolve-hooks.mjs ./scripts/gen-xxx.mjs --check`
 * 实际逻辑在 ts-hooks.mjs（同目录，ESM 模块，避免把大段代码塞进 data: URL）。
 */
import { register } from 'node:module'

register(new URL('./ts-hooks.mjs', import.meta.url))
