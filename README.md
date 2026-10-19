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

## v0.2 新增: 验证"进程被杀后能不能自己起来"
- 新增 `amap.log`: 高德广播完整内容(按 KEY_TYPE 每类 5 秒一条,不截断)。
- 每次 SERVICE_START 带 reason: button / activity / permission / boot:* / watchdog / trigger:* / STICKY-RESTART(null intent)。
- Manifest 静态触发器 TriggerReceiver: CONNECTIVITY_CHANGE、POWER_CONNECTED/DISCONNECTED、USER_PRESENT、TIME_SET、猜测的 ACC 广播。
  (targetSdk 降到 23 才能静态收到这些隐式广播,属实验设置。)
- Watchdog 改为每 60 秒一次的**非唤醒**闹钟,休眠中不唤醒车机,醒来后补发;日志里的 late 是迟到秒数。
- HEARTBEAT 增加 amap_msgs;检测到休眠会记一行 SLEPT。

### 关键测试(全程不要碰 app)
1. 打开 app 一次,确认服务在跑,然后退到桌面(不要划掉)。
2. 熄火,等 5 分钟以上,再点火。**不要打开 app**。
3. 点火后等 3 分钟,取日志。看 events.log 里 PROCESS_START 之后的第一批行:
   - `TRIGGER action=...`            → 该广播能拉起进程
   - `WATCHDOG fired late=...`      → 闹钟能拉起进程
   - `SERVICE_START reason=STICKY-RESTART` → 系统按 START_STICKY 重启
   - `BOOT_RECEIVER`                → 车机其实是冷启动
   - 什么都没有                      → 进程被清理后没有任何路径能自启(多半是被 force-stop 或自启动被拦)
