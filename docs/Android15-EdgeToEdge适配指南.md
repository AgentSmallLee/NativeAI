# Android 15+ Edge-to-Edge 适配指南

## 一、背景

从 **Android 15（API 35）** 开始，当 `targetSdkVersion >= 35` 时，系统默认强制应用采用 **edge-to-edge（边到边）** 显示模式：

- 应用内容会自动延伸到**状态栏**和**导航栏**下方
- `android:statusBarColor` 和 `android:navigationBarColor` 的纯色填充行为可能不如预期
- 如果应用未做适配，内容会直接"钻"到状态栏/导航栏下面，被遮挡

> **注意**：targetSdk 37 同属此行为范畴，本质是 Android 15 引入的变更。

---

## 二、两种适配方案

### 方案 A：快速修复 —— `fitsSystemWindows`（不推荐长期方案）

在布局的根节点添加 `android:fitsSystemWindows="true"`，系统会自动给根视图加上 `paddingTop/paddingBottom` 来避开系统栏。

```xml
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true">   <!-- 关键 -->
    ...
</LinearLayout>
```

**优点**：一行代码，改动最小
**缺点**：
- 无法自定义状态栏/导航栏颜色的过渡效果
- 在一些复杂布局（如 Drawer、CoordinatorLayout）中行为不一致
- 不符合现代 Android 的沉浸式设计趋势

---

### 方案 B：标准适配 —— `enableEdgeToEdge()` + WindowInsets（推荐 ✅）

这是 Android 官方推荐的做法，分两步：

#### 第 1 步：添加依赖

`enableEdgeToEdge()` 是 AndroidX Activity 库提供的扩展函数。

```kotlin
// build.gradle.kts (app level)
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
}
```

#### 第 2 步：修改主题（状态栏/导航栏透明）

```xml
<!-- res/values/themes.xml -->
<style name="Theme.MyApp" parent="Theme.Material3.DayNight.NoActionBar">
    <!-- 状态栏透明，让内容延伸到状态栏下方 -->
    <item name="android:statusBarColor">@android:color/transparent</item>
    <!-- 导航栏透明 -->
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <!-- 状态栏图标深色（浅色背景时使用） -->
    <item name="android:windowLightStatusBar">true</item>
    <!-- 导航栏图标深色 -->
    <item name="android:windowLightNavigationBar">true</item>
</style>
```

#### 第 3 步：在 Activity 中启用 edge-to-edge 并处理 insets

```kotlin
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ 必须在 super.onCreate() 之前调用
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 给根视图设置 padding，避开状态栏和导航栏
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = systemBars.top,      // 状态栏高度
                bottom = systemBars.bottom // 导航栏高度
            )
            insets
        }
    }
}
```

#### 第 4 步（可选）：只针对特定 View 处理

如果只想让某个具体 View（而不是整个根布局）避开系统栏：

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.recycler_view)) { view, insets ->
    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
    view.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
    insets
}
```

---

## 三、Compose 版本的适配

如果你用 Jetpack Compose，适配更简单：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MyAppTheme {
                // 使用 SafeArea 或 手动获取 insets
                val systemBarPadding = WindowInsets.systemBars.asPaddingValues()
                Box(modifier = Modifier.padding(systemBarPadding)) {
                    // 内容
                }
            }
        }
    }
}
```

Compose 中也可以用 `Scaffold` 的 `contentWindowInsets` 参数：

```kotlin
Scaffold(
    contentWindowInsets = WindowInsets.systemBars
) { innerPadding ->
    Box(Modifier.padding(innerPadding)) { ... }
}
```

---

## 四、不同场景的处理策略

| 场景 | 策略 |
|------|------|
| **普通页面（列表/表单）** | 根布局加 top/bottom padding，内容完全在安全区内 |
| **图片/视频全屏页** | 图片延伸到状态栏下，文字/按钮加 padding 避开 |
| **Bottom Sheet / 底部导航栏** | 只需要处理 bottom inset，top 不需要 |
| **沉浸式全屏（游戏/视频）** | 使用 `WindowInsetsController.hide()` 隐藏系统栏 |
| **带 Toolbar 的页面** | 让 Toolbar 背景延伸到状态栏下，内容从 Toolbar 下方开始 |

---

## 五、常见问题

### Q1：为什么升级 targetSdk 后内容突然跑到状态栏下面了？

Android 15 (API 35) 开始，targetSdk >= 35 的应用默认启用 edge-to-edge 模式。这是系统强制的行为变更，不是 bug。

### Q2：状态栏文字颜色怎么调？

- 浅色状态栏 + 深色文字：`<item name="android:windowLightStatusBar">true</item>`
- 深色状态栏 + 浅色文字：`false`

也可以在代码中动态调整：
```kotlin
WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
```

### Q3：`fitsSystemWindows` 和 `setOnApplyWindowInsetsListener` 有什么区别？

- `fitsSystemWindows` 是系统层面的自动 padding，只作用于一个 View
- `setOnApplyWindowInsetsListener` 是更灵活的手动方式，可以精确控制每个 View 的行为

**两者不要同时使用**，否则会有重复 padding。

### Q4：如何只处理状态栏，不处理导航栏？

```kotlin
val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
view.updatePadding(top = statusBars.top)
```

### Q5：Fragment 中怎么处理？

在 Fragment 的 `onViewCreated` 中设置，跟 Activity 一样的方式：
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
        insets
    }
}
```

---

## 六、迁移检查清单

升级到 targetSdk 35+ 前，逐个检查以下项：

- [ ] 所有 Activity 都调用了 `enableEdgeToEdge()`
- [ ] 主题中 `statusBarColor` / `navigationBarColor` 设为透明
- [ ] 每个页面的根布局都正确处理了 systemBars insets
- [ ] 状态栏文字/图标颜色与背景对比度足够
- [ ] 底部导航栏、FAB、底部按钮不会被系统导航栏遮挡
- [ ] 列表/RecyclerView 的最后一项完全可见（bottom padding 正确）
- [ ] 沉浸式全屏页面（视频/游戏）单独处理
- [ ] 深色模式下状态栏/导航栏图标颜色正确

---

## 七、参考资料

- 官方文档：[Enable edge-to-edge display](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- AndroidX Activity：[activity-ktx release notes](https://developer.android.com/jetpack/androidx/releases/activity)
- WindowInsets 指南：[Handle window insets](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
