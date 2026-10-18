# CarTracker 验证版

用来验证车机上的: 开机自启、前台服务保活、休眠唤醒、GPS 更新频率、高德广播内容。**不上报**,全部写本地日志。

## 构建
推到 GitHub,Actions 里 "Build APK" 跑完后在 Artifacts 下载 `CarTracker-debug`,装到车机。

## 日志位置
`/sdcard/Android/data/com.example.cartracker/files/`
- `events.log`  事件: PROCESS_START / BOOT_RECEIVER / SERVICE_* / WATCHDOG / BCAST / AMAP / HEARTBEAT / GPS
- `gps.csv`     轨迹: 含 fix 间隔 dt_ms、卫星数、是否 mock

events.log 每行方括号里:
- `pid`    进程号,变了说明进程重建过
- `up`     开机以来秒数(含休眠),归零 = 真正重启
- `awake`  不含休眠的秒数,`up - awake` ≈ 休眠时长

## 建议测试顺序
1. 打开 app,授予定位权限,点"电池白名单"并在车机系统设置里允许自启动/后台运行。
2. 在开阔处看 HEARTBEAT 的 fixes_last_min,正常约 60,即 1Hz。
3. 熄火 → 等 10 分钟 → 点火。看: up 是否归零、有无 BOOT_RECEIVER、PID 是否变化、HEARTBEAT 中间断了多久、点火后多久恢复 fix。
4. 熄火 8 小时后再点火,重复上面的观察。
5. 点火/熄火时留意 BCAST 行,里面出现的 ACC 类 action 就是你车机真实的 action,可以写进正式版。
6. 开高德导航,看有没有 AMAP 行,以及 extras 里有哪些字段。
