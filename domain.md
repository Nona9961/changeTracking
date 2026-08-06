# 领域文档

> 变更检测框架的领域概念、建模决策与边界。
> 本文只写代码无法表达的信息：机制细节与决策记录见 `.trellis/spec/changeTracking/backend/`（[索引](../.trellis/spec/changeTracking/backend/index.md)），类级契约见各文件 Javadoc，项目定位与快速上手见 [README.md](README.md)。

## 领域定位

DDD 中，聚合根在内存完成业务操作后，持久化层需要知道**哪些属性发生了变化**，才能生成精确的 UPDATE。
本框架位于领域对象与持久化之间：注册对象 → 记录基线 → 对比生成变更集 → 由持久化层消费。

- 上游是内存中的领域对象，下游是任意持久化方案（JPA / MyBatis / 自研）——框架不绑定任何持久化实现。
- 框架只回答「什么变了」，不回答「怎么存」；变更集如何映射为 SQL、何时提交，由消费方决定。

## 领域概念与语言

| 概念 | 领域含义 | 机制细节 |
|------|---------|---------|
| 变更检测器（Change Detector） | 框架角色是**检测器**：只检测已注册对象的属性变化（UPDATE 语义），不管理实体 INSERT/DELETE 生命周期 | [change-model.md](../.trellis/spec/changeTracking/backend/change-model.md)（D15） |
| 快照基线（Snapshot Baseline） | `track()` 时刻对对象状态的记录，是后续对比的参照物；业务对象之后如何修改都不影响它 | [snapshot-model.md](../.trellis/spec/changeTracking/backend/snapshot-model.md)（D5/D11） |
| 变更集（Change Set） | 一次对比计算的输出，消费方唯一需要理解的结果 | [change-model.md](../.trellis/spec/changeTracking/backend/change-model.md)（D9） |
| 双视图（Tree / Flat） | 同一差异的两种呈现：树形视图保留结构，扁平视图逐条可消费 | [change-model.md](../.trellis/spec/changeTracking/backend/change-model.md)（D9） |
| 业务标识符匹配 | 集合项的身份是业务标识而非下标——重排序不产生虚假变更 | [change-model.md](../.trellis/spec/changeTracking/backend/change-model.md)（D10） |
| 值语义（Value Semantics） | 数组是定长有序的**值**（顺序敏感），集合是无序的**容器**（标识匹配）——建模选择，非实现细节 | [snapshot-model.md](../.trellis/spec/changeTracking/backend/snapshot-model.md)（D14） |
| 排除标记（Exclusion Marker） | `excludeNew` / `excludeRemoved` 表达「不追踪」的语义标记，不是生命周期操作 | [change-model.md](../.trellis/spec/changeTracking/backend/change-model.md)（D15） |

## 建模边界

三层模型，各回答一个问题，互不越界：

- **快照层**（`domain.model.snapshot`）——回答「对象当前长什么样」。快照是**脱水**的：不持有业务对象引用，只持有可比较的节点树。边界存在的理由：切断 `track()` 之后业务对象继续变更对基线的污染，这是「基线安全」的根。
- **变更集层**（`domain.model.changeset`）——回答「什么变了」。只描述差异（路径、旧值/新值、增删项），消费方无需查看基线即可落库。它是终态输出，不再回流。
- **能力层**（`domain.capability` + `spi`）——回答「怎么生成快照、怎么比较」。策略经 SPI 可插拔，核心算法与具体策略解耦，能力可整体替换。

## 边界之外（非本领域）

- 不负责持久化与事务：变更集如何落库由消费方决定。
- 不管理实体生命周期：INSERT / DELETE 的跟踪与落库不属于本框架。
- 不绑定任何框架：快照与变更集是普通 Java 对象，任何持久化方案都可消费。
