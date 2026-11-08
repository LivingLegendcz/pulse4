<div align="center">

# Pulse4

**注重隐私、无服务器的 Android 个人健康、体征与运动数据中心**

[![English](https://img.shields.io/badge/English-232A3B?style=for-the-badge)](README.md)
[![Čeština](https://img.shields.io/badge/Čeština-232A3B?style=for-the-badge)](README.cs.md)
[![Deutsch](https://img.shields.io/badge/Deutsch-232A3B?style=for-the-badge)](README.de.md)
[![Español](https://img.shields.io/badge/Español-232A3B?style=for-the-badge)](README.es.md)
[![Français](https://img.shields.io/badge/Français-232A3B?style=for-the-badge)](README.fr.md)
[![简体中文](https://img.shields.io/badge/简体中文-0284C7?style=for-the-badge)](README.zh.md)

---

[![Android](https://img.shields.io/badge/Platform-Android%2014%2B-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Unit%20Tests-173%20通过-10B981.svg?style=flat)](app/src/test)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat)](LICENSE)

</div>

Pulse4 是一款开源、注重隐私的 Android 应用程序，旨在整合来自 **Garmin（佳明）** 和 **Withings** 生态系统的身体成分指标、运动记录与自主恢复信号。该应用遵循 **零云端依赖（Zero Cloud Dependency）** 原则：无需中心化应用服务器、无需商业订阅与账号追踪。所有敏感生理数据、加密数据库（SQLCipher）与凭据均安全保存在用户手机本地。

家庭成员或训练伙伴之间的数据自愿共享采用去中心化、基于密码学的 **Reticulum Mesh / LXMF** 点对点网状网络。

---

## 核心特性

### 1. Withings 生态系统（智能体脂秤与健康设备）
- **直接网页连接（无需付费企业开发者账户）：** 通过官方网页端端点进行无缝同步，无需托管外部重定向回调服务器。
- **全套身体成分分析：** 体重、骨骼肌量、体脂率、身体总水分（水合水平）、骨矿物质含量及内脏脂肪等级。
- **身体分段分析（四肢与躯干）：** 肢体肌肉与脂肪分布（左/右臂、躯干、左/右腿）、占总量百分比及双侧肢体对称性分析（左右差值 %）。
- **心电图（ECG）：** 支持 Withings Body Scan 与 ScanWatch 记录的心电信号、窦性心律与心房颤动（AFib）分类、记录心率及 QTc 间期测量（毫秒）。
- **后台自动静默保活：** 会话自动延期与一键快速重新连接。

### 2. Garmin 生态系统（运动与机能恢复）
- **深度生理遥测：** 夜间心率变异性（HRV / rMSSD）、深度睡眠静息心率、身体电量（Body Battery 0–100）、全天压力指数、血氧饱和度（SpO2）及呼吸频率。
- **跑步动力学与耐力分析：** 实时与潜在耐力（Stamina）、完整高阶跑步动力学（步频、垂直振幅、垂直步幅比、触地时间 GCT、左右平衡比）、心率区间及实时体能状况（Performance Condition）。
- **结构化训练规划：** 阶段间歇训练编辑器，可直接同步导出至 Garmin 运动腕表。

### 3. 高分辨率现代化界面（Jetpack Compose M3）
- **高精度微小波动曲线（±100g 至 500g）：** 多曲线折线图（`MultiSeriesLineChart`）支持独立动态缩放，设置 0.80 kg 最小视图跨度，使 200g 的日常波动占据图表 25% 的高度，不再被压平为单一水平像素线。
- **克级变化直接读取：** 测量间差异直接以克（g）为单位显示（如 `+200 g`、`-500 g`、`±0 g`），搭配触摸悬浮交互 HUD。
- **体重成分堆叠面积图：** 总体重与肌肉、脂肪分层堆叠图表，支持一键展开/折叠各成分独立统计卡片。
- **紧凑型多选下拉菜单：** 统一的筛选器栏，支持复选框自由多选指标、预设组合（恢复、心率与心电、身体成分）及时间跨度选择（7 至 365 天）。
- **图表下方无冗长文字干扰：** 所有生理指标的原理说明均收纳在 `ⓘ` 按钮弹窗中。
- **沉浸式氛围背景：** “今天”仪表盘与各项运动（跑步、步行、徒步、游泳、力量训练）均配备专属摄影氛围背景。

### 4. 去中心化网状网络共享（Reticulum Mesh）
- **去中心化点对点传输：** 无需中心化服务器或第三方数据收集。
- **端到端非对称加密：** 基于 Curve25519/Ed25519 签名与加密协议。
- **细粒度权限控制：** 自定义每项数据向特定联系人的共享权限。

---

## 模块结构

```
pulse4/
├── domain/                      # 纯 Kotlin 领域实体、单位、时区、重复数据去重
├── analytics/                   # 跑步动力学、耐力算法、海拔迟滞、百分位区间
├── connector-garmin-personal/   # Garmin Connect 同步引擎与 FIT 下载
├── connector-withings/          # Withings Web 同步引擎、身体成分与 ECG
├── sync-protocol/               # Reticulum / LXMF 加密封包层、发件箱队列、ACK 机制
├── sharing-policy/              # 权限控制、隐私区域、共享投影生成
├── ui-logic/                    # 独立于 UI 框架的展示逻辑（JVM 可测）
├── app/                         # Android 主程序（Compose M3、Room SQLCipher、Keystore、Chaquopy）
└── pulse4-vault/                # 开发日志、Obsidian 知识库与架构决策
```

---

## 构建步骤

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 运行 173 项单元测试
./gradlew testDebugUnitTest

# 构建 Debug APK
./gradlew assembleDebug

# 安装至已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 许可证

本项目基于 [Apache 2.0 许可证](LICENSE) 开源。
