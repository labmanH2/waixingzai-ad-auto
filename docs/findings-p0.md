# P0 观察结论（2026-11-07 首次录制）

设备 HUAWEI SPN-AL00 / Android 10 / 1080×2340 / dpi 480
包名 `com.etalien.booster`，主 Activity `.ui.MainActivity`
样本目录：`out/20261107-160158`（21 帧 / 11 图 / 19 份控件树 / 8k 行 logcat）

---

## 1. 奖励规则（实测绘出）

| 阶段 | 单次奖励 | 每日次数 | 解锁条件 |
|---|---|---|---|
| 阶段一 | +20 分钟 | 9 次 | 默认 |
| 阶段二 | +30 分钟（1.5 倍增幅） | 3 次 | 阶段一做完后解锁 |
| 加油包 | +10 分钟 | ? | 仍未解锁 |

- 可暂停时长实测变化：`8:58 → 9:18 → 9:38 → 9:58 → 10:18`，每次 +20 分钟，与文案一致；
- 阶段一 第 5/9 次后继续领，跑到 9/9 后阶段一显示「已完成」，阶段二变为 `0/3`；
- 时长以**秒**为单位在变（说明加速已开启时会持续扣减），判断到账需要比对"分/时"两位数，别只看 single 位。

## 2. 广告是"聚合 + 激励视频"，落地页可能是 H5 互动页

logcat 里明确是聚合 SDK（倍孜 BeiZi）在竞价：

```
D/BeiZis: request RewardedVideoAd adUnitId:123288
V/ly_ad: reward-load-bidding----bd-... / jy-... / wm-... / fw-... / blk-... / ky-... / lmg-...
```

实测出现三种落地形态：

| # | Activity | 形态 | 特征 |
|---|---|---|---|
| A | `com.byazt.tj.Stub_Standard_Portrait_Activity` | **百度百青藤 H5 激励页** | 顶部"再逛 45 秒后可领奖"倒计时；可能弹"去浏览 10 秒可减 20 秒广告时长"；可能提示"需要下滑浏览更多才能领取奖励哦" |
| B | `com.baidu.mobads.sdk.api.MobRewardVideoActivity` | 百度激励视频 | 标准视频，倒计时后出关闭 |
| C | `com.qq.e.ads.PortraitADActivity` | 腾讯优量汇激励视频 | 标准视频，倒计时后出关闭 |
| D | `com.byazt.tj.Stub_Standard_Activity` | **跳转中转页** | "正在跳转 / 打开淘宝 / 下载淘宝"，即 deeplink 落地 |

## 3. 为什么会"点到别的应用里去"

A 类 H5 页里几乎整屏都是可点热点：

- `[0,108][1080,2232]` —— 整页可点（广告素材）
- "立即查看""立享优惠""我要减广告时长""点击查看" —— 都会把人带出去
- 控件树里这些节点的 `text` 常常为空，`bounds` 却是整屏，**无法靠文本白名单简单排除**

一旦点中，SDK 走 deeplink 跳到淘宝，`E/openDeeplink` 会打日志，app 留在后台。这时候收益归零（本次那几次就是这样没领到）。

结论：**H5 广告页上除了"领奖"按钮，一个像素都不能碰**；关闭只能用系统返回键（`KEYCODE_BACK`），不要点右上角 X（X 的位置和热点重叠时同样会跳转）。

## 4. 首页可用的稳定锚点（P1 靠这些定位，不需要 OCR）

| 元素 | 资源 id | bounds |
|---|---|---|
| 看广告按钮 | `com.etalien.booster:id/UIADSubmit` | `[48,1782][1032,1914]` |
| 加速开关 | `com.etalien.booster:id/UISwitch` | `[378,180][540,300]` |
| 充值入口 | `com.etalien.booster:id/UIPCDurationRecharge` | `[531,360][673,421]` |
| 启动加速 | `com.etalien.booster:id/UIPCDurationStart` | `[837,371][987,521]` |

时长读数被拆成独立节点：`10 | 时 | 18 | 分 | 9 | 秒`，按顺序取即可。
阶段进度同样可读：`阶段一 / 已完成`、`阶段二 / 0 / 3`。

## 5. 状态机草案

```
HOME_READY ──tap(UIADSubmit)──> AD_LOADING ──> {H5_COUNTDOWN | VIDEO}
                                              │
       H5_COUNTDOWN: 解析"再逛N秒"；出现"下滑浏览"则 swipe（不 tap）
                                              │
                      N==0 或出现"领奖" ──tap(领奖)──> RETURNING
       VIDEO: 等倒计时结束/出现关闭 ──BACK──> RETURNING
                                              │
   deeplink 检测到前台不是本 app ──am start 回主界面──> RETURNING
                                              │
                         回到 HOME 且时长增加 ──> SUCCESS（否则 FAILED，记截图）
```

## 6. 风险与红线

- H5 广告页误触 = 跳转外部 app + 本次奖励作废，属于最需要防的事；
- 同一账号短时间高频领取是异常特征，需保留人类节奏（随机 3–15 秒间隔、每轮之间歇几分钟）；
- 广告填充率不稳定（logcat 里多次 `无广告填充 / timeout`），脚本必须能识别"没广告"并放弃这一轮，而不是死等。
