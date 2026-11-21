/**
 * `electron` 的最小替身，只在向量校验脚本里使用。
 *
 * 这些脚本要跑桌面端的加密与配对实现来核对手机硬编码的向量，并不启动应用。
 * 桌面源码在导入期会从 `electron` 解构出一批名字（`app`、`safeStorage`、
 * `BrowserWindow`、`session`…）。这里把用到的名字都列出来，规则统一：
 *
 * - 常量（`isPackaged`）给真实值；
 * - `app.getVersion()` 给固定的测试版本；
 * - 其余一律是「被调用就抛错」的桩 —— 任何真实的系统能力调用都会立刻暴露，
 *   而不是被一个宽容的假实现悄悄吞掉，让向量校验结果失真。
 */
const notAvailable = (name) => () => {
  throw new Error(`向量校验环境没有 Electron：${name} 不可调用`)
}

/** 造一个既能当类继承、又能当函数调用的桩。 */
function stubClass(name) {
  const Stub = function () {
    throw new Error(`向量校验环境没有 Electron：${name} 不可实例化`)
  }
  Stub.prototype = {}
  for (const method of ['on', 'once', 'removeListener', 'emit', 'set', 'get', 'handle', 'removeHandler', 'whenReady']) {
    Object.defineProperty(Stub.prototype, method, { value: notAvailable(`${name}.${method}`), configurable: true })
  }
  Object.assign(Stub, { setApplicationMenu: notAvailable(`${name}.setApplicationMenu`), fromPartition: notAvailable(`${name}.fromPartition`) })
  return Stub
}

export const app = {
  getPath: notAvailable('app.getPath'),
  setPath: notAvailable('app.setPath'),
  getVersion: () => '0.0.0-vector-check',
  getAppPath: () => '/app',
  isPackaged: false,
  whenReady: notAvailable('app.whenReady'),
  on: notAvailable('app.on'),
  quit: notAvailable('app.quit'),
  requestSingleInstanceLock: () => true
}

export const safeStorage = {
  isEncryptionAvailable: () => false,
  encryptString: notAvailable('safeStorage.encryptString'),
  decryptString: notAvailable('safeStorage.decryptString')
}

export const BrowserWindow = stubClass('BrowserWindow')
export const WebContentsView = stubClass('WebContentsView')
export const View = stubClass('View')
export const session = { fromPartition: notAvailable('session.fromPartition'), defaultSession: stubClass('session.defaultSession') }
export const shell = { openExternal: notAvailable('shell.openExternal'), showItemInFolder: notAvailable('shell.showItemInFolder'), openPath: notAvailable('shell.openPath') }
export const clipboard = { writeText: notAvailable('clipboard.writeText'), readText: notAvailable('clipboard.readText') }
export const dialog = { showOpenDialog: notAvailable('dialog.showOpenDialog'), showSaveDialog: notAvailable('dialog.showSaveDialog'), showMessageBox: notAvailable('dialog.showMessageBox') }
export const Menu = stubClass('Menu')
export const MenuItem = stubClass('MenuItem')
export const Tray = stubClass('Tray')
export const nativeImage = { createFromPath: notAvailable('nativeImage.createFromPath'), createEmpty: () => ({ isEmpty: () => true }) }
export const ipcMain = { handle: notAvailable('ipcMain.handle'), removeHandler: notAvailable('ipcMain.removeHandler'), on: notAvailable('ipcMain.on') }
export const ipcRenderer = { invoke: notAvailable('ipcRenderer.invoke'), on: notAvailable('ipcRenderer.on'), send: notAvailable('ipcRenderer.send') }
export const contextBridge = { exposeInMainWorld: notAvailable('contextBridge.exposeInMainWorld') }
export const globalShortcut = { register: notAvailable('globalShortcut.register'), unregisterAll: notAvailable('globalShortcut.unregisterAll') }
export const Notification = stubClass('Notification')
export const powerMonitor = { on: notAvailable('powerMonitor.on') }
export const screen = { getPrimaryDisplay: notAvailable('screen.getPrimaryDisplay'), getAllDisplays: () => [], on: notAvailable('screen.on') }
export const systemPreferences = { getMediaAccessStatus: () => 'granted' }
export const protocol = { handle: notAvailable('protocol.handle'), registerProtocol: notAvailable('protocol.registerProtocol') }

export default {
  app,
  safeStorage,
  BrowserWindow,
  WebContentsView,
  View,
  session,
  shell,
  clipboard,
  dialog,
  Menu,
  MenuItem,
  Tray,
  nativeImage,
  ipcMain,
  ipcRenderer,
  contextBridge,
  globalShortcut,
  Notification,
  powerMonitor,
  screen,
  systemPreferences,
  protocol
}
