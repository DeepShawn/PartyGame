# 同乐盒 PartyGame

同乐盒 PartyGame 是一个支持 Android 和网页端互联的 2～3 人小游戏合集。项目使用 Kotlin Multiplatform 共享游戏规则，Android 最低支持 API 26，网页端支持 GitHub Pages 部署。

## 在线体验

- 网页版：https://deepshawn.github.io/PartyGame/
- 管理后台：https://deepshawn.github.io/PartyGame/admin.html
- 最新 Release：https://github.com/DeepShawn/PartyGame/releases/latest
- 最新 APK：https://github.com/DeepShawn/PartyGame/releases/download/v2.1.0/app-debug.apk

## 游戏

当前包含 28 款游戏：

- 街机：反应抢按、颜色陷阱、心算抢答、记忆翻牌、贪吃蛇竞技、坦克乱斗、炸弹人、躲避竞技、硬币猜正反、数字猜猜看、找出不同、目标点击、图案记忆、凑十挑战、高低判断、颜色配对、极速记忆、极速四选一
- 棋类：五子棋、中国象棋、国际象棋、围棋、黑白棋、中国跳棋、国际跳棋、井字棋、四子棋
- 棋牌：斗地主

棋类支持点击选子、规则校验和 AI 补位。国际象棋、中国象棋、国际跳棋点击棋子后显示可走位置，其余棋类不会把所有落点全部标出。棋局结束后可以打开复盘，逐手查看历史局面。

街机支持键盘、按钮和触屏手势。贪吃蛇、坦克大战、炸弹人、躲避竞技可以在游戏区域滑动控制方向；坦克开火和炸弹人放置炸弹使用动作按钮。

## 联机方式

- 本机同屏：适合 2～3 人轮流操作。
- 局域网：Android 房主开启局域网大厅，网页或其他 Android 设备通过房间码、口令或局域网搜索加入。
- 公网房间：使用房间码和口令，通过 WebRTC/STUN 建立连接。
- 房主断线后自动转移房主。

## 账号、排行榜与管理

网页端和 Android 端支持 Supabase 邮箱注册、登录和退出。普通对局成绩自动保存，每款游戏有独立排行榜；异常分数会进入待审核状态。

管理员可以在 `admin.html` 中：

- 审核、通过或拒绝异常成绩
- 查看各游戏记录
- 按用户、设备或 IP 封禁和解除封禁

客户端只使用 Supabase publishable key，管理员权限由 Supabase RLS 和账号角色控制。不要把 service role key 放入网页或 APK。

## 本地构建

环境建议：JDK 21、Android SDK、Node.js 20、Gradle 8.11.1。

```bash
# 编译共享 JVM/JS 规则
./gradlew :shared:compileKotlinJvm :shared:compileProductionLibraryKotlinJs

# 构建网页
cd web
npm ci
npm run typecheck
npm test
npm run build

# 构建 Android APK（回到仓库根目录）
cd ..
./gradlew :app:assembleDebug
```

构建后的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，网页产物位于 `web/dist/`。

## 项目结构

```text
shared/                         Kotlin Multiplatform 游戏规则与 AI
app/                            Android 原生应用、局域网服务和引擎接入
web/src/                        React 网页端、渲染和联机逻辑
web/admin.html                  Supabase 管理后台
supabase/migrations/            数据库表、排行榜、成绩审核和 RLS
.github/workflows/              CI、GitHub Pages 和原生引擎工作流
```

## 发布

推送到 `main` 后，Pages 工作流会自动构建并部署 `web/dist`。Android APK 通过 GitHub Release 发布。发布前建议执行 Web 测试、共享核心编译和 Android Debug 构建。

## 许可证

项目代码及第三方引擎许可信息见 [`licenses/`](licenses/) 目录。
