# ECARX 模块移植清单

> 2026-10-18 · 分支 `car/lynk-ecarx`（基线 upstream v0.2.21）
> 参考：`ecarx/main`（jiopia/DiPlay-ECARX，基线 0.2.15）

## 结论先说

**9 个 ECARX 模块全部不依赖上游缺失的符号**（唯一的跨包引用 `Iap2Frame` 定义在
`shared/.../iap2/wire/Iap2Wire.kt`、`Iap2BodyReader` 在 `iap2/body/Iap2BodyReader.kt`，v0.2.21 都有）。
所以**拷过来就能编译**。

真正的门槛不在代码，在**签名、权限声明、接线点、实车标定**（见第三节）。

## A. 零依赖，可直接摘（纯平台 API）

| 文件 | 行数 | 作用 |
|---|---|---|
| `hud/EcarxCapabilities.kt` | 82 | `Class.forName` 探测 ECARX XUI/DIM 是否存在 |
| `hud/EcarxDimApi.kt` | 359 | DIM 反射 API（仪表投送） |
| `hud/EcarxKeyBroadcasts.kt` | 48 | 方控私有广播接收 |
| `hud/EcarxMicPolicy.kt` | 202 | 麦克风策略（通话/语音路由） |
| `hud/EcarxOutputSettings.kt` | 109 | 音频输出设置 |
| `airplay/EcarxSwKey.kt` | 134 | 方向盘按键学习模型（8 个动作 → keycode） |
| `hud/EcarxSwKeyIntercept.kt` | 128 | 按键事件拦截 |
| `bluetooth/CarBluetoothProfileGuard.kt` | 617 | 蓝牙 A2DP/HFP profile 让位（保留 iAP2） |

## B. 依赖上游 iap2 包（依赖已满足）

| 文件 | 行数 | 依赖 |
|---|---|---|
| `hud/EcarxHudBridge.kt` | 544 | `Iap2Frame`（wx: `iap2/wire/Iap2Wire.kt`）|
| `hud/ClusterSongState.kt` | 83 | `Iap2BodyReader` + `Iap2Frame` |

## C. 测试

`common/src/test/java/com/shilapi/compat/EcarxSwKeyTest.kt`（87 行）—— 依赖 `EcarxSwKey` + `CarPlayMediaButton`（后者上游有）。

## 二、接线 checklist（真正的活）

1. **签名必须用 AOSP 默认 platform 密钥**
   - 领克 05 固件是 `test-keys`，用该密钥签名后证书与 `framework-res` 一致
   - 否则 `signature` 级权限**全部被拒** → DIM/音量/蓝牙全废
2. **AndroidManifest 补 7 个 signature 权限**（详见 ECARX-PORT-NOTES.md 的权限表）
   - `CAR_VENDOR_EXTENSION` / `CAR_CONTROL_AUDIO_VOLUME` / `DIM_MESSAGING`
   - `OPENAPI_NAVI_PERMISSION` / `BLUETOOTH_PRIVILEGED` / `WRITE_SETTINGS` / `PACKAGE_USAGE_STATS`
3. **找 hook 点**（ECARX 版的调用点散落在它改过的文件里）：
   - `EcarxCapabilities` 初始化 → 应用启动
   - `EcarxHudBridge` → CarPlay 会话建立后（iAP2 NowPlaying / 导航指令）
   - `EcarxSwKeyIntercept` → 按键分发链最前
   - `CarBluetoothProfileGuard` → 连接/断开生命周期
4. **包名决定共存还是覆盖**（未定）

## 三、风险 / 需要实车标定的项

| 项 | 说明 |
|---|---|
| 方控按键 id 表 | ECARX 版自己也是"保守扫描"，**未标定** |
| DIM 参数 | 反射调用参数来自两份 ECARX 系统 App 的字节码，需实车确认 |
| 音乐 1–2 秒暂停 | **ECARX 版核心未解**；蓝牙让位是未验证的规避 |
| 麦克风上行 | ECARX 版**未接通**（Siri/语音不可用） |

## 四、建议顺序

1. 先摘 **A 组里低风险的**：`EcarxCapabilities`（探测）+ `EcarxSwKey` + `EcarxKeyBroadcasts` + `EcarxSwKeyIntercept`
2. 再加 **`CarBluetoothProfileGuard`**（解决"车机抢音频"的候选方案）
3. 最后做 **DIM/仪表**（`EcarxDimApi` + `EcarxHudBridge`）—— 风险最高，单独分支
