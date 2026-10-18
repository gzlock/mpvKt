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