# qqmusic-mix 项目规则

安卓 App，包名 `com.zec.qmix`。另有一个电脑网页版 `server.js` + `public/index.html`。

## 构建与验证

```powershell
# 打 APK（唯一构建入口，不要用 Gradle）
pwsh -File C:\Users\zec_i\Documents\git\qqmusic-mix\android\build.ps1

# 装到手机（USB 在线时）
adb install -r C:\Users\zec_i\Documents\git\qqmusic-mix\android\build\qmix.apk

# 装到手机（无线，手机不在电脑旁时）
adb connect 192.168.1.212:5555
adb -s 192.168.1.212:5555 install -r C:\Users\zec_i\Documents\git\qqmusic-mix\android\build\qmix.apk

# 截图（界面/视觉类 ticket 必须给）
adb exec-out screencap -p > logs\xxx.png

# 看日志
adb logcat -d -s qmix:V AndroidRuntime:E
```

`adb devices` 里出现两个设备时，所有命令都要加 `-s <序列号>`。不要用「应用宝」调试，它会起自己的 adb 服务抢 5037 端口。

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
