# qqmusic-mix 项目规则

安卓 App，包名 `com.zec.qmix`。另有一个电脑网页版 `server.js` + `public/index.html`。

## 构建与验证（Mac）

开发机是 Mac（Apple Silicon）。不要用用户手机调试。调试设备是 SDK 自带模拟器，AVD 名 `qmix`（android-34 google_apis arm64-v8a）。

```bash
ADB=~/Library/Android/sdk/platform-tools/adb

# 打 APK（唯一构建入口，不要用 Gradle）。每次 versionCode +1
bash ~/git/qqmusic-mix/android/build.sh

# 启动模拟器（-no-audio：Mac 上不出声）。放后台跑，不要卡住终端
~/Library/Android/sdk/emulator/emulator -avd qmix -no-audio -no-boot-anim -no-snapshot-save &

# 等开机完成，看设备（应看到 emulator-5554）
$ADB wait-for-device; until [ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
$ADB devices

# 媒体音量调到 0（每次开机后、播放前都要做）。按 16 次音量减
for i in $(seq 1 16); do $ADB shell input keyevent KEYCODE_VOLUME_DOWN; done
$ADB shell dumpsys audio | grep -A6 '^- STREAM_MUSIC' | tail -1   # 要看到 (speaker): 0

# 装 APK
$ADB install -r ~/git/qqmusic-mix/android/build/qmix.apk

# 拉起 App
$ADB shell am start -n com.zec.qmix/.ui.MainActivity

# 截图（文件名不要复用旧图）
$ADB exec-out screencap -p > ~/git/qqmusic-mix/logs/xxx.png

# 看是否在播：state=PLAYING(3) 是在播，PAUSED(2) 是暂停
$ADB shell dumpsys media_session | grep -A12 'package=com.zec.qmix' | grep 'state=PlaybackState'

# 看日志
$ADB logcat -d -s qmix:V AndroidRuntime:E
```

模拟器的规则：

- `cmd media_session volume --set 0` 在这个镜像上不生效，要用上面的按键办法调音量。
- 第一次打开 App 会弹通知权限框，点「Allow」。
- App 打开后默认是暂停，要点「播放」才会出声。

Windows 旧环境用 `android/build.ps1`，Mac 不用它。

产物：`android/build/qmix.apk`。

## 硬约束

- JDK 用 17（Mac：`/opt/homebrew/opt/openjdk@17`，Homebrew 装的 `openjdk@17`），不要用 JDK 21。原因：build-tools 34.0.0 带的 d8 8.2.2 读到 JDK 21 javac 生成的匿名类 `MethodParameters` 属性会 NPE。
- Android SDK：Mac 上是 `~/Library/Android/sdk`（platform 34，build-tools 34.0.0）。
- 不用 Gradle，不用第三方依赖。
- `.cookie` 和 `android/assets/cookie.txt` 是登录凭证，不进 git。
- 调试播放必须静音，不要突然出声。
- 不要为了这个 App 去换音乐平台（云音乐版权少，酷狗主号怕封）。

## 代码规则

- 最简单能用的方案，写最少的代码。
- 不做推测性抽象、不加预留扩展点、不写不可能发生的分支防御。
- 改动要小，不顺手重构无关代码。
- 句柄要关，线程要停，回调要移除。

## 记住的东西

项目长期结论写在 `.dsh/memory.md`。开工前先读。
