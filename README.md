# 外星仔加速器「看广告得时长」自动化

用一台电脑 + 一根数据线，通过 adb 驱动安卓手机，自动完成外星仔加速器里
「看广告领免费加速时长」的任务。

**只自动化"点击"，广告始终真实播放**——不抓包、不逆协议、不改包、不伪造曝光。

> 本项目仅用于学习 Android UI 自动化与状态机设计。使用前请阅读文末的免责声明。

---

## 效果

单账号单日实测（1080×2340 / Android 10）：

| 档位 | 单次奖励 | 每日次数 | 完成情况 |
|---|---|---|---|
| 阶段一 | +20 分钟 | 9 | 9 / 9 |
| 阶段二 | +30 分钟 | 3 | 3 / 3 |
| 加油包 | +10 分钟 | 9 | 9 / 9 |

可暂停时长 `10:43:30 → 16:43:30`，**+6 小时**，正好等于理论满额 360 分钟。
全程 21 次广告，脚本自己完成点按钮、识别广告类型、关闭广告、校验到账，约 45 分钟。

## 原理

```
┌──────────┐  uiautomator dump   ┌──────────┐   input tap   ┌──────────┐
│  adb     │ ──────────────────> │  状态机   │ ────────────> │  手机     │
│ (电脑端)  │ <────────────────── │ (Python) │               │  真机     │
└──────────┘   控件树 / 截图      └──────────┘               └──────────┘
```

1. `uiautomator dump` 拿到界面控件树（外星仔是 Flutter 应用，但暴露了语义树和
   自有资源 id，比如 `com.etalien.booster:id/UIADSubmit`），**不需要 OCR 或图像识别**；
2. 首页点「看广告 领时长」按钮；
3. 识别广告类型（聚合 SDK，落地形态有好几种），按类型走不同的收场逻辑；
4. 回到首页后轮询余额与阶段进度，确认奖励到账（奖励是延迟发放的，最多等 90 秒）。

### 已适配的广告形态

| 形态 | Activity 特征 | 收场方式 |
|---|---|---|
| 快手激励视频 | `com.kwad.sdk.api.proxy.app.KsRewardVideoActivity` | 点 `close_button`，无则 65 秒后点右上角 X |
| 优量汇激励视频 | `com.qq.e.ads.PortraitADActivity` | 识别「奖励将于 N 秒后发放」，点 X，处理「确认要离开吗」弹窗 |
| 百度激励视频 | `com.baidu.mobads.sdk.api.MobRewardVideoActivity` | 同上 |
| 聚合 `com.ptg.ptgapi` | `PtgRewardVideoPortraitActivity` | 超时兜底 + 重启 |
| 百度百青藤 H5 | `com.byazt.tj.Stub_Standard_Portrait_Activity` | 等「再逛 N 秒可领奖」归零 → 点「领奖」；提示下滑则上滑 |

### 三条硬规则（踩过坑才总结出来的）

1. **广告页除关闭控件外一个像素都不碰**。H5 广告整页 `[0,108][1080,2232]` 都是热点，
   点错就是 deeplink 跳淘宝京东，本次奖励作废。
2. **绝不点「跳过」**。`ksad_top_toolbar_close_tip` 的 id 里也含 `close`，
   误点会提前结束广告导致不发奖。
3. **兜底用 `force-stop` 而不是反复按返回**。广告 Activity 赖在最上层时，
   `monkey` 拉不回首页，只能杀进程重开。

## 环境要求

- Windows / macOS / Linux 均可（本项目在 Windows 10 上开发）
- Python 3.9+，**零第三方依赖**
- [platform-tools](https://developer.android.com/tools/releases/platform-tools)（adb）
- 安卓手机：开发者选项 + USB 调试，插线后点「允许」

## 快速开始

```bash
git clone https://github.com/<you>/<repo>.git
cd <repo>

python auto.py probe                          # 只看余额和任务状态，不动手
python auto.py run                            # dry-run，只打印计划
python auto.py run --act --rounds 3           # 真跑 3 轮
python auto.py run --act --rounds 21 --gap 25 55 --max-minutes 95   # 跑满一天
```

跑之前建议先手动走一遍流程，顺便用观察者采点样本：

```bash
python observe_p0.py probe            # 体检：分辨率、包名、控件树是否可见
python observe_p0.py record --duration 600
```

### 参数

| 参数 | 说明 |
|---|---|
| `--act` | 真的点击；不加就是 dry-run |
| `--rounds N` | 跑几轮 |
| `--gap A B` | 轮间隔随机区间（秒），默认 20–45 |
| `--max-minutes M` | 总时长上限 |
| `--package` | 目标包名，默认 `com.etalien.booster` |
| `--serial` | 多设备时指定序列号 |

## 目录

```
auto.py              控制器：状态机 + 动作白名单 + 到账校验
observe_p0.py        观察者：体检 / 连续采样（只读，不点击）
docs/findings-p0.md  侦察记录：奖励规则、广告类型、控件锚点
out/                 运行时产物（截图、控件树、logcat）——已 gitignore
```

## 已知限制

- 坐标以 1080×2340 为参照等比缩放，异形屏/折叠屏可能偏，建议先 `probe` 核对；
- 百度百青藤 H5 那条分支只做了逻辑，实战样本少；
- App 更新可能改资源 id 或页面结构，届时 `probe` 一眼就能看出锚点失效；
- 广告由聚合 SDK 竞价决定，同一轮抽到哪家不可控，脚本对未知形态走「超时 + 重启」兜底
  （实测兜底的那几次奖励照样到账）。

## 免责声明

- 本项目与外星仔加速器官方无关，未获得任何授权；
- 自动化操作**大概率违反该应用的用户协议**，可能导致账号被限制、加速时长被清零，
  请自行承担风险，建议先用小号验证；
- 请勿用于商业用途或大规模批量操作，那已经不是"个人自动化"而是广告欺诈了；
- 项目按 MIT 协议开源，作者不对使用后果负责。
