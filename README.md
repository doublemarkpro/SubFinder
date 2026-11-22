# 幕匣字幕 · SubFinder

幕匣是一款用电视遥控器操作的 NAS 字幕工具，适用于 Chromecast with Google TV 4K 和运行 Android 9 或更高版本的兼容电视设备。通过 SMB 连接 NAS 后，选择影片即可搜索字幕，并按影片文件名保存到同一目录。

[下载 1.5 安装包](https://github.com/doublemarkpro/SubFinder/releases/download/v1.5/NAS-Subtitles-TV-1.5.apk) · [查看版本记录](https://github.com/doublemarkpro/SubFinder/releases)

## 在电视上使用

1. 在 NAS 上开启 SMB，让电视和 NAS 连接同一局域网。打开幕匣后，选「发现局域网 NAS」或输入 NAS 地址，使用有字幕写入权限的账号登录。
2. 选择共享和影片目录。可以为单部影片选字幕，也可以批量补齐一个目录里缺少的简中字幕。应用会按 NAS 和账号记住上次浏览的目录。
3. 单集在 The Subtitle DB 和 Subtitle Cat 都找不到简中时，可以用遥控器到 SubHD 选择整季包。按网站提示完成下载后，核对匹配的集号再保存。已有简中的集数会跳过，同一集有多个候选时可以手动选版本。也支持从 NAS 打开 ZIP、RAR 和 7z 字幕包。

1.5 可直接覆盖安装之前使用相同签名的版本。已保存密码或使用访客登录时，下次启动会自动返回上次目录。

## 从源码构建

准备 JDK 17 和 Android SDK Platform 35，并通过 `ANDROID_HOME` 或项目根目录的 `local.properties` 指定 SDK 路径。在项目根目录运行以下命令。

```sh
./gradlew :app:assembleDebug
```

Windows PowerShell 使用 `.\gradlew.bat :app:assembleDebug`。生成的调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。如需覆盖已安装的发布版，请使用上方的签名安装包。
