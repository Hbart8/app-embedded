# 嵌入式开发工具包

一个面向嵌入式开发调试场景的本地 Android 工具应用。

当前项目重点是把手机变成一个随身可用的调试面板，优先支持：

- USB 串口调试
- BLE 调试
- Hex / Text 转换
- CRC16 / CRC32 / SUM8
- 常用进制转换
- 本地日志保存、复制、分享

## 当前功能

### 1. 工具箱

- `Hex / Text` 转换
- `CRC16 / Modbus`
- `CRC32 / Checksum`
- `进制转换`

### 2. USB 串口调试

- 刷新和枚举 USB 串口设备
- 申请 USB 权限
- 打开 / 关闭串口
- 发送 `HEX / ASCII`
- 接收显示 `HEX / ASCII`
- 常用预设：
  - `Modbus 9600`
  - `UART 115200`
  - `AT 指令`
- 串口日志复制 / 分享 / 清空

### 3. BLE 调试

- 扫描 BLE 设备
- 选择设备并连接
- 发现特征
- 按特征写入数据
- 开关通知
- BLE 日志复制 / 分享 / 清空

### 4. 主题

- 支持 `日间 / 夜间` 模式切换

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- Android SDK 34
- `usb-serial-for-android`

## 运行环境

- Android Studio
- JDK 17
- Android SDK 34
- 最低 Android 版本：`minSdk 24`

## 本地构建

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 输出位置：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 手机安装

已打开 USB 调试后，可通过 `adb` 安装：

```powershell
D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 权限与硬件能力

项目当前使用了这些能力：

- `android.hardware.usb.host`
- `android.hardware.bluetooth_le`
- BLE 相关权限
- Android 12+ 的 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`

## 当前仓库说明

这是一个正在持续迭代中的本地调试工具项目，当前已经具备基础可用能力，后续可以继续扩展：

- BLE 特征按 Service 分组显示
- 更多串口协议助手
- 更完整的校验工具
- 更成熟的 UI 设计

## 仓库地址

- GitHub: <https://github.com/Hbart8/app-embedded>
