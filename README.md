# 传APK（TV 接收器）

给电视用的**极简 APK 接收器**。打开应用即启动一个 HTTP 服务器，手机用浏览器把 APK
传过去，电视自动弹出安装器，装完删掉临时文件。按返回键退出即释放，**不设常驻服务**。

- 系统要求：**Android 8.0（API 26）及以上**
- 技术栈：**纯 Java + 原生系统 API**，不引入 Kotlin / AndroidX / Compose / 任何第三方库
- 包体目标：Release APK 不超过 100 KB

## 怎么用

1. 电视上打开「传APK」，屏幕会显示一个网址，例如 `http://192.168.1.23:8080`
2. 手机连**同一个 Wi-Fi**，用浏览器打开那个网址（手机上不用装任何 App）
3. 页面上选择 APK 文件，点「发送并安装」
4. 电视自动弹出安装器，用遥控器确认安装
5. 装完后 App 自动删除接收到的临时文件
6. 不用时按遥控器返回键退出，HTTP 服务器随之关闭

## 两个权限，为什么需要

1. **网络权限（INTERNET）**
   - 用途：监听局域网端口，接收手机传来的文件。
2. **安装未知应用（REQUEST_INSTALL_PACKAGES）**
   - 用途：拉起系统安装器。Android 8.0 起这是必需项。
   - 首次使用需要在系统设置里允许本应用安装其他应用，**这是安卓的安全底线，绕不过去**。

> 本应用不申请任何存储权限：接收的临时文件放在应用自己的缓存目录里。

## 从 GitHub Actions 下载 APK（不用本机环境）

1. 打开仓库页面，进入 **Actions** 标签
2. 左侧选择 **Build APK** 工作流，进入最近一次运行
3. 页面底部 **Artifacts** 区域下载 zip，解压得到 APK

- **Debug 包**：push 到 main 即自动构建，用于快速验证。
- **Release 包**：需要先在仓库 **Settings → Secrets and variables → Actions** 配置以下四项，
  再手动触发（Actions 页面 → Build APK → Run workflow）：

  | Secret 名 | 内容 |
  | --- | --- |
  | `ANDROID_KEYSTORE_BASE64` | keystore 文件的 base64 |
  | `KEYSTORE_PASSWORD` | keystore 密码 |
  | `KEY_ALIAS` | 密钥别名 |
  | `KEY_PASSWORD` | 密钥密码 |

## 本地构建（需要 JDK 17 + Android SDK）

在项目根目录执行：

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

产物路径：`app\build\outputs\apk\debug\app-debug.apk`

## 项目结构

```
app/src/main/
├─ AndroidManifest.xml
├─ java/com/skyeward/tvrelay/
│  ├─ MainActivity.java    界面、启停服务器、触发安装、删除临时文件
│  ├─ TinyHttp.java        手写 ServerSocket HTTP 服务器（只认 GET / 和 PUT /upload）
│  └─ ApkProvider.java     手写 ContentProvider，替代 AndroidX FileProvider
└─ res/
   ├─ drawable/ic_launcher.xml
   └─ values/strings.xml
```
