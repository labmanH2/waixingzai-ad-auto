# 外星仔自动化（Android App）

用**无障碍服务**替代 adb，让自动化跑在手机本机上——不用连电脑、不用 Shizuku、不用 root。

这是 `auto.py` 的移植版：同样的策略（首页只点领取、广告页只点关闭、绝不点「跳过」），
换成手机原生 API 实现。

## 和 PC 版的差别

| 能力 | PC 版（auto.py） | App 版 |
|---|---|---|
| 读界面 | `uiautomator dump` | 无障碍节点树（不卡「等空闲」，更快） |
| 点击 | `input tap` | `dispatchGesture` |
| 返回 | `keyevent BACK` | `performGlobalAction` |
| 杀进程重来 | `am force-stop` | ❌ 没权限，改为连按返回 ×3 + HOME + 重新拉起 |
| 运行形态 | 连电脑跑 | 手机独立运行，带悬浮窗 + 推送 |

## 功能

- **批量跑广告**：默认 21 轮，跑满当日三档（9 + 3 + 9）
- **首页状态机**：区分「就绪 / 加载中 / 加载失败 / 已看完」，分别点领取、等待、点重试、停机
- **今日已看完自动停**：首页出现「今日广告已看完，请明日再来」立即结束
- **秒退外跳**：素材跳到京东/淘宝/微信等应用时立刻按返回，1.2 秒去抖
- **悬浮窗**：运行时左上角常驻，显示「第 N/21 轮 · 广告中 42s · VIDEO」
- **Server酱推送**：结束时推送结果，SendKey 在主界面填写，不填则跳过

## 安装

1. 下载 Release 里的 `wzauto.apk`（调试签名，需要允许「安装未知来源应用」）
2. 打开 App → ① 打开无障碍设置 → 找到「外星仔自动化」→ 开启
3. 点 ③ 悬浮窗权限 → 在系统弹窗里允许
4. 填 SendKey（可选）→ 点 ② 开始

> 建议先跑 3 轮试水，稳定后再放开 21 轮。

## 编译

不依赖 Gradle，用 SDK 自带工具直接打包（`build.ps1`）：

```powershell
# 需要 JDK 17 + Android SDK（platforms;android-30 + build-tools;34.0.0）
powershell -ExecutionPolicy Bypass -File build.ps1
```

流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 塞入 `classes.dex` →
`zipalign` → `apksigner`。

## 已知限制

- **拿不到 `force-stop` 权限**：广告 Activity 赖在最上层时，只能靠返回键和重新拉起
- **H5 广告拿不到奖励**：百度百青藤这类页面对无障碍同样不可见，22 秒后主动放弃
- **锁屏会中断**：靠 `SCREEN_BRIGHT_WAKE_LOCK` 保持屏幕常亮，请插着电跑
- App 更新若改动资源 id 或页面结构，需要重新校准锚点

## 免责声明

与外星仔加速器官方无关。自动化操作大概率违反该应用的用户协议，可能导致账号受限，
请自行承担风险。
