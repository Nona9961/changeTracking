# changeTracking 代码审查报告

> **审查日期**：2026-06-17
> **审查人**：AI（pi agent）
> **审查范围**：全量源码（api 121 行 + core 2070 行）+ 全量测试（2773 行）
> **审查方法**：逐文件通读源码与测试，交叉验证关键路径，按「架构合理性 / 性能 / 测试覆盖」三类产出

---

## 背景说明（影响问题定性）

本报告的问题定性基于以下作者确认的背景，阅读时请一并参考：

1. **本库由作者本人设计**，架构骨架（sealed interface + record 建模、SPI 扩展、Unit of Work、循环引用处理、双视图变更集）反映的是设计意图，质量较高。
2. **代码实现由 AI 生成**，且由 Gemini / GPT / OUPS / DeepSeek 轮流接手。多模型接手导致风格不一致、边界场景遗漏——这是 A1 / A2 / A6 等实现疏漏的成因，也意味着后续修复需注意「统一基线」而非零散打补丁。
3. **缓存等非功能性优化是有意暂缓**：反射元数据缓存、类型判断缓存、增量计算等尚未引入，是因为「还没到非功能性阶段」。因此本报告中 P1 / P2 / P3 等「缺缓存」项**不视为缺陷**，而是「已知暂缓项 / 未来优化点」，单独归入附录而非缺陷列表，避免误导。
4. **测试 1:1 的比例有迷惑性**：行数看似健康，但覆盖集中在快乐路径，边界 / 并发 / 性能 / 类型变化几乎空白。

基于上述背景，本报告区分三类：
- **🔴 缺陷（Bug / 功能缺失）**：实现与设计意图或契约不符，应修
- **🟡 设计讨论 / 代码质量**：可改可不改，取决于演进方向
- **🟢 增强项**：功能补充，非紧急
- **⏸ 已知暂缓项**：作者已确认有意延后，记录备查，不计入缺陷

---

## 总体评价

设计骨架质量明显高于实现细节质量。sealed interface / record / SPI / 循环引用处理 / 业务标识符集合匹配等核心设计到位，但落地实现存在若干真实 bug（类型变化产出错误数据、数组静默丢失、不可变契约破坏、接口标识符查找不对称），测试在边界与并发维度几乎空白。建议优先修复 P0 缺陷并补对应测试，再考虑非功能性优化。

---

## 问题总览

| 编号 | 类别 | 严重度 | 标题 | 状态（2026-08-06 更新） |
|------|------|--------|------|------|
| A1 | 架构 | 🔴 P0 | 类型变化场景产出语义错误的 FieldChange | ✅ resolved（WU-A1） |
| A2 | 架构 | 🔴 P0 | 数组类型完全未处理，静默丢数据 | ✅ resolved（WU-A2） |
| A3 | 架构 | 🔴 P1 | TrackingConfiguration 假不可变，契约破坏 | ✅ resolved（WU-A3） |
| A4 | 架构 | 🔴 P1 | record 可变状态泄漏（ObjectNode/CollectionNode/ChangeNode） | ✅ resolved（WU-A4） |
| A5 | 架构 | 🟡 P2 | TrackingCapability 泛型设计削弱类型安全 | ✅ resolved（WU-A5） |
| A6 | 架构 | 🟡 P1 | SPI 标识符查找：接口不递归，与父类不对称 | ✅ resolved（WU-A6+F2） |
| A7 | 架构 | 🟢 P3 | UnitOfWork 命名与经典 UOW 语义冲突 | ✅ resolved（WU-A7） |
| A8 | 架构 | 🟢 P3 | UnitOfWork 缺生命周期管理方法 | ⏸ 延后（D15） |
| A9 | 架构 | 🟢 P3 | ChangeSet 转换逻辑高度重复 | ✅ resolved（WU-A9） |
| P4 | 性能 | 🟡 P2 | 集合比较排序触发 toString，无业务必要 | ✅ resolved（WU-P4/P5） |
| P5 | 性能 | 🟢 P3 | diffObjectChildren 用 TreeSet 排序字段 | ✅ resolved（WU-P4/P5） |
| P6 | 性能 | 🟢 P3 | processComplexObject 的 stream + peek 副作用 | ✅ resolved（WU-A8 + 终审补完） |
| P7 | 性能 | 🟢 P3 | IdentityHashMap 大对象图内存压力 | 📝 已文档化 |
| T1 | 测试 | 🔴 P0 | 类型变化场景零覆盖（对应 A1） | ✅ resolved（WU-A1） |
| T2 | 测试 | 🔴 P0 | 数组零覆盖（对应 A2） | ✅ resolved（WU-A2） |
| T3 | 测试 | 🔴 P1 | 并发安全零覆盖 | ✅ resolved（WU-TB） |
| T4 | 测试 | 🔴 P1 | 性能 / 大规模零覆盖 | ✅ resolved（WU-TB） |
| T5 | 测试 | 🟡 P2 | transient 字段处理未验证 | ✅ resolved（WU-TB） |
| T6 | 测试 | 🟡 P2 | 接口继承链标识符查找未覆盖（对应 A6） | ✅ resolved（WU-A6+F2） |
| T7 | 测试 | 🟡 P2 | calculateChanges 重复调用语义未覆盖 | ✅ resolved（WU-TB） |
| T8 | 测试 | 🟡 P2 | Configuration 不可变性未验证（对应 A3） | ✅ resolved（WU-A3） |
| T9 | 测试 | 🟡 P2 | record 可变性泄漏未验证（对应 A4） | ✅ resolved（WU-A4） |
| T10 | 测试 | 🟢 P3 | 字段排序稳定性未覆盖（对应 P5） | ✅ resolved（WU-P4/P5） |
| T11 | 测试 | 🟢 P3 | 非集合项 identifier 冗余调用未覆盖 | ✅ resolved（WU-TB） |

> **修复状态说明**：A1-A9（除 A8 延后）、P4-P7、T1-T11 已修复并通过 `mvn clean test`（core 172 + api 24 + 终审新增 1 = 197 测试全绿，2026-08-06 实测）；消费方 projectScaffolding- 同步后 132 测试全绿。
> **T0 / T0b**：审查后新发现的测试基础设施问题（surefire 不识别 JUnit 5、Mockito 沙箱 attach 失败），见任务 verification.md，已在 WU-TB 修复（surefire 3.2.5 + junit-jupiter-engine + Byte Buddy -javaagent）。

> P1 / P2 / P3（反射缓存、isValueType 缓存、增量计算）见附录「已知暂缓项」，不计入缺陷。

---

## 一、架构不合理

### 🔴 A1. 类型变化场景产出语义错误的 FieldChange

> ✅ **resolved**（WU-A1，commit 741e21e）——按 D5-D9/D12 决策修复：`FieldChange` → `ValueChange`（仅承载基本值变更，oldValue/newValue 保证为业务值）+ 新增 `ObjectFieldChange`（对象/集合字段整体替换，携带 ValueNode oldNode/newNode）；`extractValue` 不再泄漏节点实例。消费方同步：projectScaffolding issue 009。

**定位**：`ValueNodeComparisonStrategy.diffNode` + `extractValue`

**问题**：

```java
// extractValue 对非 Primitive/Null 节点返回 node 本身
private Object extractValue(final ValueNode node) {
    if (node instanceof PrimitiveNode pn) return pn.value();
    if (node instanceof NullNode) return null;
    return node;   // ← ObjectNode / CollectionNode 直接返回
}
```

当 `oldNode` 与 `newNode` 类型不兼容时（如 `ObjectNode → PrimitiveNode`、`CollectionNode → ObjectNode`、`ObjectNode → NullNode`），`diffChildren` 的两个 `instanceof` 分支均不命中 → 返回 emptyList → 走到 `isTypeChanged` 分支 → 产出 `FieldChangeNode(path, extractValue(oldNode), extractValue(newNode))`。

**结果**：`FieldChange.oldValue()` / `newValue()` 持有的是 **ValueNode 内部对象**（ObjectNode / CollectionNode 实例），而非业务值。这是内部模型泄漏到外部 `Change` API。

**影响**：下游消费者（projectScaffolding 的 `PoReconstructor`）若依赖 `FieldChange.oldValue/newValue` 是业务值来做 `equals` 判断或序列化，会直接出错。触发场景不罕见——字段从对象降级为 null、集合被替换为对象都会命中。

**建议**：类型变化时不应产出 `FieldChange`，应产出专门的 `TypeChangedChange`；或在 `extractValue` 里对 ObjectNode/CollectionNode 递归提取业务值（但语义仍模糊，前者更诚实）。

---

### 🔴 A2. 数组类型完全未处理，静默丢数据

> ✅ **resolved**（WU-A2，commit 5215dac）——按 D10/D14 决策修复：新增 `ArrayNode`（数组 = 值语义，顺序敏感，equals 内容比较 + 防御拷贝）；值类型元素数组 → ArrayNode，复杂对象元素数组 → CollectionNode 递归（复用 identifier 匹配）。消费方零影响（载荷仍为数组实例）。

**定位**：`ValueNodeSnapshotStrategy.toValueRecursive` + `isValueType`

**问题**：`toValueRecursive` 只分支 Collection / Map / 复杂对象；`isValueType` 不识别数组。

数组（`byte[]` / `String[]` / `Object[]`）既不是值类型也不是 Collection，会落到 `processComplexObject` → `ReflectionUtils.getAllFields(数组类)` 返回**空列表**（数组无声明字段）→ 数组被快照成**空 ObjectNode**，内容全部丢失，变更检测彻底失效。

**影响**：DDD 实体里 `byte[]`（二进制）、`String[]`（标签）不罕见。静默丢数据是最坏的一类 bug——无异常、无日志，数据悄无声息消失。

**建议**：`toValueRecursive` 增加数组分支——基本类型数组可整体当值类型（`PrimitiveNode`），对象数组转成 `CollectionNode`（按索引）。

---

### 🔴 A3. TrackingConfiguration 假不可变，契约破坏

> ✅ **resolved**（WU-A3，commit 1412229）——构造器 `Map.copyOf` / `Set.copyOf` 防御拷贝 + getter 返回不可变集合（任何 add/put 抛 UnsupportedOperationException）；T8 不可变契约测试守护。

**定位**：`TrackingConfiguration`（构造函数 + `@Getter`）

**问题**：

```java
public TrackingConfiguration(Map<...> identifierExtractors, Set<Class<?>> customValueTypes, Set<String> customValuePackages) {
    this.identifierExtractors = identifierExtractors;  // ← 直接持有，不拷贝
    ...
}
// @Getter 暴露原始引用：getCustomValueTypes().add(x) 可修改配置
```

注释声称「此类是不可变的」，但：(1) 构造时不防御性拷贝；(2) getter 暴露可变集合。只有 `EMPTY` 单例用了 `Collections.emptyXxx`。`DefaultTrackingCapabilityProvider.create()` 用 `Map.copyOf` 间接保护了默认链路，但直接 `new TrackingConfiguration(userMap, ...)` 的用法（测试里就有）会暴露。

**影响**：配置被意外修改后，已构建的 capability 行为不可预测。

**建议**：构造时 `Map.copyOf` / `Set.copyOf`，getter 返回不可变视图。与 `ChangeSet` 做了 `List.copyOf` 不一致——作者知道该拷贝，没贯彻。

---

### 🔴 A4. record 可变状态泄漏

> ✅ **resolved**（WU-A4，commit f20a6eb）——按 D11 决策：`ObjectNode` / `CollectionNode` 由 record 改为 final class + 只读方法 API（`field`/`forEachField`、`size`/`item`/`forEachItem`），不暴露任何集合引用（外部写编译级不可能）；`ContainerChangeNode` / `ContainerChange` 保留 record + 构造期 `List.copyOf`；equals/hashCode/toString 内容语义 + 循环引用防栈溢出（顺带修复 record 在循环图上炸的问题）。API 变化（无调用方，非 breaking）→ projectScaffolding issue 008。

**定位**：`ObjectNode` / `CollectionNode` / `ContainerChangeNode` / `ContainerChange`

**问题**：
- `ObjectNode(Map<String,ValueNode> fields, ...)`：`processComplexObject` 传入 `HashMap`，`fields()` accessor 返回同一引用，外部可 `put`
- `CollectionNode(Collection<ValueNode> items)`：传入 `ArrayList`，`items()` 可 `add`
- `ContainerChangeNode.children`、`ContainerChange.children` 同理

record 本应是不可变值对象，却暴露可变视图。`ValueNodeSnapshotStrategy` 内部用 `fieldsMap.putAll` 填充 ObjectNode 的 map——意味着快照构建过程中 map 是可变的，若并发读到半成品 ObjectNode 会出问题。

**影响**：快照/变更集的不可变性契约被破坏，潜在的并发读半成品数据风险。

**建议**：record compact constructor 里 `Map.copyOf` / `List.copyOf`。对小 map 成本可忽略，换来真正的不可变。

---

### 🟡 A5. TrackingCapability 泛型设计削弱类型安全

> ✅ **resolved**（WU-A5，commit 2096b8f）——`SnapshotStrategy<S extends Snapshot<?>>` 参数化，`createSnapshot` 返回 `S`；`getSnapshotStrategy()` 返回 `SnapshotStrategy<S>`（raw 消除）；ChangeTracker 用 `getSupportedSnapshotType()` checked cast 显式类型守卫，不再 `@SuppressWarnings` 强转。源码级 breaking（仅影响 SPI 实现者）→ projectScaffolding issue 010。

**定位**：`TrackingCapability` 接口 + `UnitOfWork.calculateChangesWithCapture`

**问题**：`getSnapshotStrategy()` 返回 raw `SnapshotStrategy`（无泛型），`getComparisonStrategy()` 返回 `ComparisonStrategy<S>`（有泛型）。根因是 `SnapshotStrategy.createSnapshot` 返回 `Snapshot<?>` 无法绑定到 `S`。

导致 `UnitOfWork.calculateChangesWithCapture` 必须 `@SuppressWarnings("unchecked")` 强转 `(S) oldSnapshot`，注释承认「基于架构性信任」。

**影响**：用户若混用不同 capability 的快照会运行时 ClassCastException，编译期查不出。

**建议**：要么 `SnapshotStrategy<S>` 参数化（让 `createSnapshot` 返回 `S`），要么接受 raw 但在 UOW 层做一次 `instanceof` 防御。当前「既 raw 又强转又 suppress」是最差组合。

---

### 🟡 A6. SPI 标识符查找：接口不递归，与父类不对称

> ✅ **resolved**（WU-A6+F2，commit 42d3013）——按 D18 决策：类链 × 每层接口链统一递归（对 type 及每个父类：先查精确 key，再递归接口链，visited 防环）；T6 测试改写为应命中。

**定位**：`ValueNodeSnapshotStrategy.findExtractor`

**问题**：

```java
// 3. 遍历接口（仅直接接口，不递归）
for (final Class<?> iface : type.getInterfaces()) { ... }
```

场景：`interface Base {getId()}` ← `interface Sub extends Base` ← `class Impl implements Sub`。为 `Base` 配提取器，`Impl.getInterfaces()` 返回 `[Sub]`（不含 Base），查找失败 → 回退 identityHashCode → 集合匹配失效。

测试只覆盖了直接接口（`Product implements Identifiable`），没测接口继承链。**父类链递归了，接口却没递归，不对称**——典型的多模型接手遗漏。

**建议**：接口也递归遍历（`Set<Class<?>> visited` 防环），或用等价于 Spring `ClassUtils.getAllInterfaces` 的逻辑。

---

### 🟢 A7. UnitOfWork 命名与经典 UOW 语义冲突

> ✅ **resolved**（WU-A7，commit 33f7874）——按 D16 决策彻底改名：`UnitOfWork` → `ChangeTracker`（包 `domain.model.unitofwork` → `domain.model.tracking`）、`registerClean/New/Removed` → `track`/`excludeNew`/`excludeRemoved`、`UnitOfWorkFactory` → `ChangeTrackerFactory`；消费方 `UnitOfWorkProvider` → `ChangeTrackerProvider` 等同步（projectScaffolding issue 011）。

**定位**：`UnitOfWork.registerNew` / `registerRemoved`

**问题**：注释明确「排除机制，不生成变更」。经典 Fowler UOW 中 new/removed 恰是生成 INSERT/DELETE 的核心。这里框架只做 UPDATE 追踪，INSERT/DELETE 交给下游。

**判断**：设计选择本身合理（职责分离），但继承经典命名却改语义，容易误导。**非缺陷，设计讨论项**。

**建议**：要么改名（`exclude` / `markUntracked`），要么 README 顶部强警告「本 UOW 仅追踪 UPDATE，new/removed 是排除标记非生命周期」。

---

### 🟢 A8. UnitOfWork 缺生命周期管理方法

> ⏸ **延后**（D15，与 A8 一并记录）——`clear()`/`reset()`/`isTracking` 公开化等生命周期方法不在本任务范围：消费方实证（每次操作新建 ChangeTracker + 手动重新 `track` 重登记）已覆盖语义，新 API = 契约承诺 + 概念负担（YAGNI）。`calculateChanges()` 幂等视图语义已确认并补 T7 测试固化。

**定位**：`UnitOfWork`

**问题**：
- 无 `clear()` / `reset()`：用完只能丢弃重建
- 无 public `isTracking(entity)` 查询
- `registerClean` 后无法刷新快照（`isTracking` 直接 return）；想「重新基线」只能先 `registerRemoved` 再 `registerClean`，反直觉
- `calculateChanges()` 可重复调用但每次全量重算，无「已计算」标记

**建议**：作为增强项，按实际使用痛点逐步补充。

---

### 🟢 A9. ChangeSet 转换逻辑高度重复

> ✅ **resolved**（WU-A9，commit ae65109）——三套转换（toChange / toChangeWithRelativePath / toChangeWithContext）合一为 `convert(node, parentPath, collectionFieldName, useRelativePath)`（-46 行）；顺手清理 F4（未消费的 parentIsCollection 参数移除）；F3（容器 children 与顶层列表重复展开）确认为 D9 双视图设计语义，Javadoc 固化。ChangeNode 中间层合并（G6）记录为后续候选，不在本任务范围（D13）。

**定位**：`ChangeSet`（`toChange` / `toChangeWithRelativePath` / `toChangeWithContext`）

**问题**：三个近乎相同的私有方法都做 ChangeNode→Change 转换 + 路径计算 + `collectionFieldName` 提取。`currentParentIsCollection` / `currentCollectionFieldName` 计算逻辑重复 3 次。维护时改一处漏两处的风险高。

**建议**：抽公共方法。

---

## 二、性能瓶颈

> 注：反射缓存、类型判断缓存、增量计算等见附录「已知暂缓项」，此处只列当前阶段就应处理或值得记录的性能问题。

### 🟡 P4. 集合比较排序触发 toString，无业务必要

> ✅ **resolved**（WU-P4/P5，commit 03c3e71）——`allIdentities` 改用 LinkedHashSet 按插入序迭代（old 项序在前，新增项追加在后），砍掉排序与 `String.valueOf`（verification 修正：原报告「直接按插入序输出」建议不完整——原实现是 HashSet 无序，必须 LinkedHashSet 才有确定性）。路径文本中的 identity 表示（`buildItemPath`）保留，属路径构造非排序。

**定位**：`ValueNodeComparisonStrategy.sortIdentities`

**问题**：

```java
sorted.sort(Comparator.comparing((Object identity) -> identityTypeName(identity))
        .thenComparing(identity -> String.valueOf(identity)));  // ← 每个 identity 调 toString
```

排序 O(N log N) 仅为「测试输出稳定」。`String.valueOf(identity)` 对复杂 identifier（未好好实现 toString）有性能与正确性风险。

**建议**：`groupByIdentity` 已用 `LinkedHashMap`（保留插入序），直接按插入序输出即可，砍掉排序和 toString。

---

### 🟢 P5. diffObjectChildren 用 TreeSet 排序字段

> ✅ **resolved**（WU-P4/P5，commit 03c3e71）——`allKeys` 改用 LinkedHashSet：以 old 节点字段声明序为基准，new 新增字段追加在后（非字典序）；T10 测试固化（zebra/alpha/mango 声明序）。

**定位**：`ValueNodeComparisonStrategy.diffObjectChildren`

**问题**：`allKeys` 用 `TreeSet` 按字典序。副作用：变更顺序变字典序而非声明序，对消费方（PoReconstructor 按字段序生成 SQL）不友好；TreeSet 构造 O(K log K)。

**建议**：改 `LinkedHashSet`（先 old 后 new 插入序），保序又更快。

---

### 🟢 P6. processComplexObject 的 stream + peek 副作用

> ✅ **resolved**（WU-A8 commit 9eaaeff：stream+peek 改 for 循环；终审 2026-08-06 补完：临时 map + putAll 一并去除——`fieldsMap` 改 LinkedHashMap 直接 `putIfAbsent` 填充，顺带修复 P5 声明序链路（此前 putAll 进 HashMap 丢失声明序），新增测试 `fieldIteration_shouldFollowDeclarationOrder` 固化）。

**定位**：`ValueNodeSnapshotStrategy.processComplexObject`

**问题**：为支持循环引用（先放空 node 到 visited），先建 `HashMap fieldsMap` 放进 ObjectNode，再 stream `collect(toMap, LinkedHashMap)` 建临时 map，最后 `fieldsMap.putAll`。多一次 map 复制 + stream peek 副作用（反模式，无法 parallel）。

**建议**：for 循环直接往 `fieldsMap` put（它已先置入 visited），省临时 map 和 putAll。

---

### 🟢 P7. IdentityHashMap 大对象图内存压力

> 📝 **已文档化**（2026-08-06）——changeTracking README「内存特性」章节：快照创建期间 visited 缓存持有对象引用、快照为脱水 ValueNode 树（不持业务对象引用）、变更集每次实时转换不缓存。固有特性定性不变，无代码改动。

**定位**：`ValueNodeSnapshotStrategy.createSnapshot` / `toValueRecursive`

**问题**：`visited` 持有所有访问对象引用直到快照完成。大聚合根（万级 items）内存翻倍。

**判断**：这是快照方案的固有代价，非缺陷。但无大小限制 / 分块策略，至少应文档说明内存特性。

---

## 三、测试不全面

### 🔴 T1. 类型变化场景零覆盖（对应 A1）

> ✅ **resolved**（WU-A1，commit 741e21e）——`nestedObject_becomesNull_shouldBeReported` 断言重写（原断言固化泄漏行为：oldValue 即 ObjectNode 实例）；补 ObjectNode→PrimitiveNode / CollectionNode→ObjectNode 方向 + 新类型（ValueChange/ObjectFieldChange）断言。

无 ObjectNode→PrimitiveNode / CollectionNode→ObjectNode 等节点类型变化测试——正是 A1 bug 的触发场景。应先写复现测试再修。

### 🔴 T2. 数组零覆盖（对应 A2）

> ✅ **resolved**（WU-A2，commit 5215dac）——`ArraySnapshotTests` 8 个：{1,2,3}→{3,2,1} 顺序敏感固化、多维 deepEquals、防御拷贝（track 后改业务数组不污染旧快照）。

全测试集 grep 不到 `int[]` / `Object[]` / `byte[]`。代码也不处理，静默丢数据无测试守护。

### 🔴 T3. 并发安全零覆盖

> ✅ **resolved**（WU-TB，commit 6e71be8）——`ChangeTrackerTest.ConcurrentAccessTests`（多线程并发 track/calculateChanges，特征测试）；非线程安全为现状已知特征，已文档标注。

`UnitOfWork` 的三个集合都是普通 HashMap/Set，非线程安全。无并发测试。projectScaffolding 有 `RequestContextPropagatingTaskDecorator` 跨线程传播上下文的场景——若 UOW 跨线程共享会出问题。需明确「非线程安全」文档或加同步 / 并发测试。

### 🔴 T4. 性能 / 大规模零覆盖

> ✅ **resolved**（WU-TB，commit 6e71be8）——`ValueNodeSnapshotStrategyPerformanceTest`（万级 items 快照耗时基准）。

无性能测试、无大对象图测试、无大集合测试。反射零缓存等问题在现有小对象测试里完全暴露不出来。应加：万级 items 快照 / 比较耗时基准 + 内存占用基准。

### 🟡 T5. transient 字段处理未验证

> ✅ **resolved**（WU-TB，commit 6e71be8）——`TransientFieldTests` 特征测试固化现状：仅过滤 static 不过滤 transient，transient 字段会被快照（语义留待后续明确，测试守护现状防漂移）。

代码只过滤 `static` 不过滤 `transient`。无测试验证 transient 字段是否应被追踪。需明确语义（transient 通常表示不该持久化）并加测试。

### 🟡 T6. 接口继承链标识符查找未覆盖（对应 A6）

> ✅ **resolved**（WU-A6+F2，commit 42d3013）——`IdentifierExtractionTests` 9 个：类链 × 每层接口链（父类实现的接口、接口继承链、类接口混合链）均命中；原 2 个 shouldMiss 特征测试改写为应命中。

只测直接接口，没测 `interface A extends B` + `class C implements A` + 为 B 配提取器。

### 🟡 T7. calculateChanges 重复调用语义未覆盖

> ✅ **resolved**（WU-TB，commit 6e71be8）——`calculateChanges_repeatedCalls_shouldReturnSameChangeSet`：连续两次调用返回相同变更集（幂等视图特征，D15 固化）。另：`calculateChanges_forDirtyCleanObject_shouldCallComparisonAndCreateChangeSet` 已覆盖单次语义（verification 修正：非零覆盖）。

无测试验证：registerClean → calculateChanges → 再修改 → 再 calculateChanges。当前每次全量重算，引入脏标记优化后需测试守护。

### 🟡 T8. Configuration 不可变性未验证（对应 A3）

> ✅ **resolved**（WU-A3，commit 1412229）——`TrackingConfigurationTest` 不可变契约：getter 集合 add/put 抛 UnsupportedOperationException + 构造防御拷贝（外部修改原集合不影响内部状态）。

无测试验证 `getCustomValueTypes().add(x)` 破坏配置。

### 🟡 T9. record 可变性泄漏未验证（对应 A4）

> ✅ **resolved**（WU-A4，commit f20a6eb）——`NodeImmutabilityTests` 6 个：`fields().put()` / `items().add()` 已不存在（编译级封死），改为验证只读 API 行为 + equals/hashCode 内容语义 + 循环引用不炸。

无测试验证 `objectNode.fields().put()` / `collectionNode.items().add()` 破坏不变性。

### 🟢 T10. 字段排序稳定性未覆盖（对应 P5）

> ✅ **resolved**（WU-P4/P5，commit 03c3e71）——多字段全部变更按声明序输出（zebra/alpha/mango 非字典序）+ 新增字段按声明序追加在后。

无测试验证变更顺序是声明序还是字典序。TreeSet 问题在现有少字段测试里不可见。

### 🟢 T11. 非集合项 identifier 冗余调用未覆盖

> ✅ **resolved**（WU-TB，commit 6e71be8）——非集合项 identifier 特征测试固化现状（identityHashCode 回退）；冗余调用优化（仅集合项场景提取 identifier）记录为后续候选，非本任务范围。

`processComplexObject` 对所有复杂对象都调 `extractIdentifier`（含非集合项），非集合项 identifier 应为 null 但仍执行 `findExtractor` 全链遍历，浪费。无测试验证非集合项 identifier 语义。

---

## 修复优先级建议

> **修复完成注记（2026-08-06）**：下方优先级清单全部处理完毕——P0 立即项（A1/A2/T1/T2）、P1 尽快项（A3/A4/A6/T3/T4）、P2 排期项（A5/P4/T5-T9）、P3 收尾项（A7/A9/P5/T10-T11）均已 resolved（见各条目标记）；A8 延后（D15）；P6 已修复（WU-A8 + 终审补完临时 map）。

| 优先级 | 问题 | 理由 |
|--------|------|------|
| **P0 立即** | A1 类型变化 FieldChange | 真实 bug，产出错误数据，下游消费会出错 |
| **P0 立即** | A2 数组未处理 | 静默丢数据，最坏类别 |
| **P0 立即** | T1 / T2 补测试 | 守护 A1 / A2，先复现再修 |
| **P1 尽快** | A3 / A4 不可变性 | 契约正确性，改动小 |
| **P1 尽快** | A6 接口递归 | 与父类不对称的真实 bug |
| **P1 尽快** | T3 并发 + T4 性能基准 | 暴露隐藏风险，为优化提供守护 |
| **P2 排期** | A5 泛型 / P4 排序 / T5-T9 测试补全 | 设计与正确性改进 |
| **P3 收尾** | A7 命名 / A8 生命周期 / A9 去重 / P5-P7 / T10-T11 | 打磨项 |

---

## 附录：已知暂缓项（作者确认有意延后，不计入缺陷）

以下项作者已确认「非功能性阶段未到，有意暂缓」，记录备查，待进入性能优化阶段时再评估：

| 编号 | 项 | 说明 | 未来触发条件 |
|------|-----|------|-------------|
| ⏸ P1 | 反射字段元数据缓存 | `ReflectionUtils.getAllFields` 每次 `getDeclaredFields()` + 遍历继承链，`field.setAccessible(true)` 每字段每次都调。建议 `ConcurrentHashMap<Class<?>, List<Field>>` 缓存 | 进入性能优化阶段 / 出现大对象图慢查询 |
| ⏸ P2 | isValueType / findExtractor 缓存 | 同一类型反复判断 / 查找，建议缓存 `Class<?> → boolean` 与 `Class<?> → Function` | 同上 |
| ⏸ P3 | calculateChanges 增量计算 | 当前每次全量重算所有 cleanObjects，建议脏标记机制 | 出现多次 calculateChanges 调用场景 |

---

## 附：审查方法与已验证项

- **审查方法**：逐文件通读 34 个源文件 + 9 个测试文件，交叉验证 UnitOfWork → SnapshotStrategy → ComparisonStrategy → ChangeSet 完整调用链，grep 确认数组 / 并发 / transient / 性能测试覆盖情况。
- **已确认设计正确之处**（非问题，记录以备复查）：sealed interface 穷尽建模、SPI ServiceLoader 发现机制、循环引用 IdentityHashMap 处理（含自引用 Collection/Map）、业务标识符集合匹配（含重复项 occurrence 后缀）、双视图变更集（树形 / 扁平）、字段隐藏处理（子类覆盖父类）。
- **未深入验证项**：未实际编译运行测试（仅静态审查）；未验证与下游 projectScaffolding `PoReconstructor` 的真实集成行为（A1 影响需下游确认）。
