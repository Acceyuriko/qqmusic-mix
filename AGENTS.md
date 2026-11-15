# qqmusic-mix 项目规则

安卓 App，包名 `com.zec.qmix`。另有一个电脑网页版 `server.js` + `public/index.html`。

## 构建与验证

```powershell
# 打 APK（唯一构建入口，不要用 Gradle）
pwsh -File C:\Users\zec_i\Documents\git\qqmusic-mix\android\build.ps1

# 连应用宝里的安卓环境（当前唯一可用设备）
adb connect 127.0.0.1:5555

# 装 APK
adb -s 127.0.0.1:5555 install -r C:\Users\zec_i\Documents\git\qqmusic-mix\android\build\qmix.apk

# 拉起 App（任务已存在时能直接拉起）
adb -s 127.0.0.1:5555 shell am start -n com.zec.qmix/.ui.MainActivity

# 找 App 在哪个显示上（id 每次可能变，见过 2 和 3）
adb -s 127.0.0.1:5555 shell "dumpsys activity activities | grep -E 'Display #|com.zec.qmix/.ui.MainActivity'"

# 截图：必须指定显示，并且用 cmd /c 重定向（PowerShell 的 > 会弄坏 PNG）
cmd /c "adb -s 127.0.0.1:5555 exec-out screencap -p -d 3 > logs\xxx.png"

# 看日志
adb -s 127.0.0.1:5555 logcat -d -s qmix:V AndroidRuntime:E
```

应用宝调试的规则：

- 第一次必须在应用宝窗口里手动点开「串串」。它会给 App 分配一个虚拟显示。
- 分配好之后，`adb install -r` 不会删任务，`am start` 能自己把 App 拉起来。进程被杀干净就要再手动点一次。
- 不要用 `am start --display N`，shell 没这个权限，会报 `SecurityException`。
- 显示 0 是黑屏，别截。要截截图里有界面的那个显示 id。
- 手机不在电脑旁时不要用手机，那台 `192.168.1.212:5555` 已经断开。

产物：`android/build/qmix.apk`。

## 硬约束

- JDK 用 `C:\Users\zec_i\apps\jdk17`，不要用 JDK 21。原因：build-tools 34.0.0 带的 d8 8.2.2 读到 JDK 21 javac 生成的匿名类 `MethodParameters` 属性会 NPE。
- Android SDK：`C:\Users\zec_i\apps\android-sdk`（platform 34，build-tools 34.0.0）。
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
