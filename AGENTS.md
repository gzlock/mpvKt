# AGENTS.md - Android/Kotlin 项目开发规范

## ⚠️ 关键操作禁令 (CRITICAL RESTRICTIONS)

### 1. 禁止自动/频繁执行 Gradle 编译命令
- **严禁**在每次完成代码修改后，主动执行 `./gradlew.bat :app:compileDebugKotlin --no-daemon`（或任何变体的全量编译命令）来验证代码。
- **原因**：全量编译（尤其是带有 `--no-daemon`）耗时极长且极度消耗系统资源。
- **替代做法**：
  - 编写代码时请仔细检查语法、类型匹配和逻辑正确性。
  - **除非用户明确要求**“帮我验证编译”或“检查是否有编译错误”，否则**不要**主动运行 Gradle。
  - 如果确实需要排查编译问题，优先通过静态代码分析、检查 imports、类型和上下文来解决，或者提示用户自行在 IDE 中运行。

---

## 🛠️ 开发与代码风格规范
- **语言**：Kotlin
- **架构**：MVVM (ViewModel + StateFlow/LiveData)
- **状态管理**：涉及页面跳转、滚动位置保持等交互时，必须在 ViewModel 中做好状态缓存，防止组件重新挂载时丢失。

---

## 🔒 R8/ProGuard 规则规范（避免“debug 正常、release 失败”）

### 为什么 debug 能用、release 不能用
- debug 的 `isMinifyEnabled = false`：不裁剪、不混淆，所有类都保留，反射始终可用。
- release 的 `isMinifyEnabled = true`：R8 会移除“未被直接引用”的类/成员。注意本项目虽有 `-dontobfuscate`（不重命名），但**仍会裁剪未使用的类**；通过反射、SPI、JNI 等间接引用的代码会被误删，问题只在 release 暴露。

### 引入新依赖时，命中以下任一条就必须评估并加 keep 规则
1. **反射加载**：`Class.forName` / `getDeclaredConstructor` / `newInstance`，或按字段名、方法名反射，以及运行时注解。
2. **SPI / ServiceLoader**：`META-INF/services` 注册的安全 Provider、加密算法、XML 解析器等。
3. **JNI / native**：被 native 代码回调的类与方法。
4. **按类名动态加载算法实现的库**：如 JSch、smbj、jcifs、BouncyCastle。
5. **注解驱动 / 事件总线 / 依赖注入**中反射实例化的类（如 mbassador/mbassy）。
6. **按字段名反射的序列化模型**（Gson/Jackson/Moshi 等）；`kotlinx.serialization` 生成的序列化器被直接引用，通常无需手动处理。

### AAR 与纯 JAR 的关键区别
- **AAR** 可携带 `consumerProguardRules`，依赖时自动生效，通常不用手写规则。
- **纯 JAR**（JSch、commons-net 都是 JAR）一般不带 Android consumer 规则，需手动在 `app/proguard-rules.pro` 补规则。

### 防患于未然的工作流（不要等发版实测才发现）
1. 在**引入依赖的同一次改动**里就完成 R8 评估并加好规则，不延后。
2. 先查该库官方文档的 ProGuard/R8 建议；必要时解压构件，检查是否存在 `Class.forName`、`META-INF/services`、`native` 方法。
3. 规则按库分组，并用注释写清“为什么保留”（具体反射机制），与现有写法保持一致。
4. 所有规则统一维护在 `app/proguard-rules.pro`，已保留哪些包以该文件为准。

### 常用规则写法
- `-keep class <包名>.** { *; }`：整库保留（反射类库最常用）。
- `-dontwarn <包名>.**`：抑制可选/缺失依赖产生的告警。
- `-keepclassmembers class <类> { <成员>; }`：只保留特定成员。
- 依赖泛型或注解信息时：`-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*`。