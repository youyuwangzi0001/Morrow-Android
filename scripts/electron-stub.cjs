/**
 * E3 跨语言验证里的 `electron` 替身（CommonJS）。
 *
 * `scripts/e3-desktop-relay.mjs` 用桌面端 `relay.ts` 的真实实现起一个 Node 服务端，
 * 只为了验证「桌面 Node ↔ 手机 Kotlin」在同一条协议上能不能真的连上，并不启动应用。
 * `relay.ts` 顶部会 import `electron`（`app` / `safeStorage`），而 MorrowAndroid 目录
 * 下没有 electron 包，于是打成 CJS 后 `require('electron')` 直接失败。
 *
 * 这里把用到的名字都定义出来，规则统一：常量给真实值，其余「一调用就抛错」——
 * 任何真的走到系统能力的路径都会立刻暴露，而不是被宽容的假实现吞掉。
 */
const notAvailable = (name) => () => {
  throw new Error(`E3 验证环境没有 Electron：${name} 不可调用`)
}

/** 既能被当构造函数 new、又能被当函数调用的桩。 */
function stub(name) {
  const S = function () {
    throw new Error(`E3 验证环境没有 Electron：${name} 不可实例化`)
  }
  S.prototype = {}
  Object.assign(S, { setApplicationMenu: notAvailable(`${name}.setApplicationMenu`) })
  return S
}

const app = {
  getPath: notAvailable('app.getPath'),
  setPath: notAvailable('app.setPath'),
  getVersion: () => '0.0.0-e3-verify',
  getAppPath: () => '/app',
  isPackaged: false,
  whenReady: notAvailable('app.whenReady'),
  on: notAvailable('app.on'),
  quit: notAvailable('app.quit'),
  requestSingleInstanceLock: () => true
}

const safeStorage = {
  isEncryptionAvailable: () => false,
  encryptString: notAvailable('safeStorage.encryptString'),
  decryptString: notAvailable('safeStorage.decryptString')
}

module.exports = {
  app,
  safeStorage,
  BrowserWindow: stub('BrowserWindow'),
  WebContentsView: stub('WebContentsView'),
  session: { fromPartition: notAvailable('session.fromPartition') },
  shell: { openExternal: notAvailable('shell.openExternal') },
  clipboard: { writeText: notAvailable('clipboard.writeText'), readText: notAvailable('clipboard.readText') },
  dialog: { showOpenDialog: notAvailable('dialog.showOpenDialog'), showSaveDialog: notAvailable('dialog.showSaveDialog') },
  Menu: stub('Menu'),
  MenuItem: stub('MenuItem'),
  Tray: stub('Tray'),
  nativeImage: { createFromPath: notAvailable('nativeImage.createFromPath') },
  ipcMain: { handle: notAvailable('ipcMain.handle'), removeHandler: notAvailable('ipcMain.removeHandler') },
  globalShortcut: { register: notAvailable('globalShortcut.register'), unregisterAll: notAvailable('globalShortcut.unregisterAll') },
  screen: { getPrimaryDisplay: notAvailable('screen.getPrimaryDisplay'), getAllDisplays: () => [] }
}
