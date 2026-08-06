# 结构文档

> 项目的代码地图：模块划分、包结构与依赖方向（概述级）。
> 类级职责与契约见各文件 Javadoc；目录结构约定见 [directory-structure.md](../.trellis/spec/changeTracking/backend/directory-structure.md)（本文描述本项目现状，不照搬模板）。

## 模块划分

| 模块 | 内容 | 项目内依赖 |
|------|------|-----------|
| `change-tracking-api` | 公共 API 入口：`ChangeTrackerFactory`（Builder 模式，SPI 能力发现与选择） | 依赖 `change-tracking-core`（工厂需返回 core 中的 `ChangeTracker` 类型） |
| `change-tracking-core` | 全部实现：领域模型、SPI 契约、内部实现 | 无项目内依赖 |

> 现状说明：api 模块依赖 core 模块（与 directory-structure.md 通用约定「api 不依赖 core」不同），
> 原因是 `ChangeTrackerFactory.build()` 直接返回 core 的 `ChangeTracker` 类型。

## 包地图（change-tracking-core）

| 包 | 职责 |
|----|------|
| `spi` | 扩展点契约：`TrackingCapabilityProvider`（ServiceLoader 发现）、`SnapshotStrategy`、`CreationContext` |
| `domain.model.snapshot` | 快照模型：`ValueNode` 密封节点树与 `ValueNodeSnapshot`（脱水数据，无业务引用） |
| `domain.model.changeset` | 变更集模型：`ChangeNode` 树、`Change` 扁平视图与 `ChangeSet`（纯输出数据） |
| `domain.model.tracking` | `ChangeTracker`：变更检测器，持有能力与快照基线，对外提供 track / calculateChanges |
| `domain.capability` | 能力契约与默认策略：`TrackingCapability`、`TrackingConfiguration`、`ComparisonStrategy` 及默认比较实现 |
| `internal.snapshot` | 默认快照实现：`ValueNodeSnapshotStrategy`（反射脱水） |
| `internal.capability` | 默认能力实现：`DefaultTrackingCapability` 与 `DefaultTrackingCapabilityProvider` |
| `internal.util` | 内部工具：`ReflectionUtils` |

## 依赖方向

自底向上：

1. `spi`、`domain.model.snapshot`、`domain.model.changeset` —— 底层契约与纯数据模型，不依赖其他包
2. `domain.capability` → `spi` + `domain.model.snapshot` + `domain.model.changeset`（策略契约引用快照/变更类型）
3. `domain.model.tracking` → `domain.capability` + 两个 model 包 + `spi`（`ChangeTracker` 组装能力）
4. `internal.*` → `domain.*` + `spi`（实现依赖契约与模型，不反向依赖）
5. `api`（模块）→ `change-tracking-core`（仅工厂入口依赖实现模块）

## 阅读起点

按调用链从入口到实现：

1. `ChangeTrackerFactory`（`api`）——框架入口，理解能力如何被发现与组装
2. `ChangeTracker`（`domain.model.tracking`）——核心门面：track / calculateChanges 语义
3. `ValueNodeSnapshotStrategy`（`internal.snapshot`）——对象如何脱水为快照树
4. `ValueNodeComparisonStrategy`（`domain.capability`）——快照如何对比为变更集
