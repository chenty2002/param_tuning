# CoupledL2 形式化验证参数自动调优方案

## 目标与判定边界

在 `chisel/coherence-param-tuning` 的 `VerifyTop` 上，从用户给定的离散参数空间中，寻找**满足参数语义约束、成功生成并编译 SystemVerilog、且 JG 在固定验证窗口内没有发现反例**的最小配置。这里的“可行”仅指这套固定参数性质、输入假设和时间预算下的结果；超时未发现反例应记录为 `bounded_no_cex`，不能写成性质已证明。只有按规定顺序完整搜索到首个可行配置，才能声称它是该搜索空间及排序规则下的最小值。

当前实现及实测结果见 [`../param_tuning/README.md`](../param_tuning/README.md)。缩小下限后的搜索先按静态约束批量剪枝，再保持原目标排序，连续验证并独立复核首个可行项；原始分母与批量拒绝证据保留。新增层级约束 `l3.sets > l2.sets`，要求 L3 组索引空间严格大于 L2，并要求两个 L2 的实际 channel-A 目录 miss 分配 MSHR 覆盖分别到达。新性质的证据保存在 `runs-l3-space/`，旧 `runs/` 仅作历史记录。当前入口开放 `ram.width`，固定 RAM beatBytes=1、三层 blockBytes=2/channelBytes=1。以下清单仍作为约束及后续扩展设计，不表示所有参数族都已开放。`chisel/coherence-param-tuning` 仅用于调参算法及其专用验证顶层；`verilog/coherence-param-tuning` 保存生成的 Verilog、固定 JG 配置和逐候选证据。保留两处已有文件及改动。

## 现状与必须同步的参数位置

| 层级 | 当前值 | 需要联动的位置 |
| --- | --- | --- |
| 两个 CoupledL2AsL1 | `ways=2, sets=2, blockBytes=2, channelBytes=1, mshrs=4` | `L2Param`、L1 地址拆分、MSHR 遍历 |
| 两个 CoupledL2 | `ways=2, sets=4, blockBytes=2, channelBytes=1, mshrs=4` | `L2Param`、L2 地址拆分、MSHR 遍历、L3 的 `clientCaches` 描述 |
| HuanCun L3 | `ways=2, sets=4, blockBytes=2, channelBytes=1, mshrs=6` | `HCCacheParameters`、L3 地址拆分和目录数组回退形状 |
| RAM/链路 | `TLRAM` 地址范围 `0..0x1f`、`beatBytes=1`；`TLFragmenter(1, 2)` | RAM 数据位宽 `8 * beatBytes`、fragmenter、各层 TL beat/block 匹配 |

当前 `VerifyTop.scala` 中 `l1/l2/l3_*Bits`、`mshrs=4` 和 L3 目录数组回退维度也是硬编码；只替换三个 cache 构造参数会让监测点错位或无法 elaboration。L3 的 `clientCaches` 现用 `ways=2+2`、`sets=4`，必须先确认其含义，再从 L2 参数派生，不能机械地设成 L2 的 ways。原有 `consistency_spec()` 等一致性、互斥性、包含性断言全部注释或从调参顶层移除，JG 只运行下面定义的参数性质；不要靠 SV 后处理删断言，因为删除结果难以审计。`AutoVerify` 当前会改写 `set_verify.py` 并复制文件到固定绝对路径，不适合作为搜索入口。目标 Verilog 目录已有五个辅助文件和 `setup.sh`，后者按命令行参数生成 Tcl；调参流程改用一份固定 `verify.tcl`，不复用该可变配置入口。

## 配置与单一参数入口

采用 Python 标准库可读取的 JSON。每个待调参数给闭区间 `min/max/step`，未列出的参数固定为当前基线值；整数序列为 `min, min+step, ... <= max`。所有候选、静态检查、Scala elaboration 和评分使用同一份解析后的参数快照。第一版只允许三个层级各自的 `ways/sets/blockBytes/mshrs/channelBytes` 与 `ram.beatBytes`，不开放尚未梳理依赖的参数。

```json
{
  "parameters": {
    "l1.ways": {"min": 2, "max": 4, "step": 1},
    "l2.sets": {"min": 4, "max": 8, "step": 4},
    "l3.mshrs": {"min": 6, "max": 8, "step": 2},
    "ram.beatBytes": {"min": 1, "max": 2, "step": 1}
  },
  "search": {"max_candidates": 0}
}
```

上例仅展示格式，不表示这些组合已验证。`max_candidates=0` 表示不限制候选数；非零值到达时停止并报告“搜索未完成”。JSON 只负责参数范围和搜索上限，不能改变 JG 时限、线程、性质或假设；这些内容写在固定源码/Tcl 中，结果记录它们的版本及 hash。Python 拒绝未知字段、非整数、`step<=0`、空范围与重复参数；运行器将解析后的完整候选保存为 JSON，并通过固定的 `key=value` 命令行参数传给 Scala 顶层，避免新增 Scala JSON 依赖。所有构造实例使用这同一份候选值。两个 L1、两个 L2 保持同一层级参数；若以后需要实例间不对称，再扩展配置。

## 参数语义检查清单

**配置常量**在 Chisel elaboration 时用带具体参数名的 `require` 检查，失败记为 `static_reject`；固定值之间的关系不是时序性质，不能用“JG 限时无反例”代替静态检查。**运行行为**由新的、统一以 `param_` 命名的断言在 JG 中检查。原 `VerifyTop` 的一致性、互斥性、包含性和 eventuality 断言/假设均不参与；原有 `inputAddr === 0` 也移除，否则新增 sets 无法被访问。库内部已有断言若仍生成，需在 JG 中明确只选择 `param_*` 性质并在结果中列出排除项，不能靠文本后处理暗中删除。

| 参数族 | elaboration 时必须成立的关系 | 专用形式化断言：在已接受事务或有效目录项上检查 |
| --- | --- | --- |
| `ways` / 组相连 | 各层 `ways >= 2`；目录实际形状为 `sets × ways`，way 编码能表示全部 way；L3 描述每个 L2 的 `clientCaches` 几何参数从实际 L2 派生，当前 `ways=2+2` 的额外两项须先查明含义 | `param_way_index`：读写选择的 way 总在范围内；`param_way_isolation`：对一组一个 way 的写入不修改同组其他 way；`param_unique_live_tag`：同一组两个有效 way 不持有相同 tag。覆盖同组至少两个不同有效 way，以确认组相连结构真正可用 |
| `sets` / tag / 地址 | 各层 `sets >= 2` 且为 2 的幂；`setBits=log2(sets)`、`offsetBits=log2(blockBytes)`、`tagBits=addressBits-bankBits-setBits-offsetBits > 0`，所有位切片非空；RAM 可映射地址空间至少覆盖每层全部 set 和两个 tag 值 | `param_address_decode`：被访问 block 的 `(tag,set,offset)` 与地址位切片一致；同一 block 内各字节映射到相同 tag/set，不同 set 的地址不会别名；目录读写的 set 索引与请求地址一致。对每个配置内的 set 建立可达覆盖，无法覆盖时记录原因 |
| `blockBytes` | 各层均为正的 2 的幂，`blockBytes >= channelBytes`；L1/L2/L3、TL 支持的 transfer size、RAM 地址空间和 L3 client 描述相容；目前 L3 `SourceA` 还要求 `blockBytes / beatBytes == 2` | `param_block_boundary`：整块事务起始地址按 block 对齐，后续 beat 不跨 block；`param_block_size`：完整块传输的 beat 数、TL size 和字节总数均等于配置值；块内 offset 的重组不丢失首尾字节 |
| `channelBytes` | 每层正的 2 的幂，整除 `blockBytes`；上下游 TL channel/fragmenter 接口的 beat 宽度、支持的 size 和 mask 位数相容 | `param_beat_shape`：A/C/D 通道有效传输的 `data/mask` 宽度、beat 序号和最后一拍位置与配置匹配；`param_beat_stable`：ready 为低时 payload 保持稳定；`param_block_assembly`：分拍重组后字节顺序、有效字节 mask 和总长度不变。只对有数据的消息要求完整块数据 |
| `mshrs` / 并行度 | 下限先冻结为 L1/L2/L3 的 `4/4/6`，调整下限需先审查实现；实例数、索引宽度和状态采样数量一致。L3 `Slice` 实际 `mshrsAll == mshrs + 2`，不能把预留的两个误算为普通并行槽 | `param_mshr_capacity`：同一周期普通在途事务数不超过配置的 mshrs；`param_mshr_unique`：有效槽的事务 ID/地址匹配关系不冲突，响应只释放匹配槽一次；`param_mshr_progress`：有空槽且满足握手条件时可接收请求，满槽时不覆写现有事务。覆盖至少两个普通 MSHR 同时有效，证明并行度不是只存在于声明中 |
| `ram.beatBytes` | 正的 2 的幂；数据宽度为 `8*beatBytes`，写掩码宽度为 `beatBytes`；`TLFragmenter` 两端 size/beat 与 RAM 兼容，当前固定 `TLFragmenter(1, 2)` 需随参数检查或派生 | `param_ram_lanes`：每个写 mask 位只影响对应字节；`param_ram_data`：在没有中间覆盖写的情况下，读回已接受写入的每个有效字节，且通过 fragmenter 不截断、重复或交换字节。该检查用明确的握手与读写顺序作为前提 |

表中每类动态性质都要绑定实际请求/响应或目录更新的**有效信号**，只在对应操作发生时触发；不得把 DUT 的预期行为写进 `assume`。形式化环境仅约束输入地址落在 RAM 映射范围、请求遵守 ready/valid 和复位协议，不再固定地址为 0。逐类断言/覆盖的生成模板固定；若按 `sets/ways/mshrs` 展开，每个候选应有按参数计算的预期数量，不能强求与基线数量相同。记录实有名称/数量并与预期比较。若监测信号无法从当前模块取到，先补最小只读观测点，不能删除该性质后继续把候选判为可行。若某类覆盖始终不可达，先检查环境假设和地址空间；未解释的不可达不计为有效的参数验证。改变任何断言、假设或固定 JG 配置后，所有候选都必须重新比较。

固定 JG 基线在 `verilog/coherence-param-tuning/verify.tcl` 中写死：分析本候选的 `VerifyTop.sv` 和目录内现有五个辅助文件，`elaborate -bbox_a 65536`、`reset reset`、`clock clock`、`set_prove_time_limit 1800s`、`set_engine_threads 4`、`set_proofgrid_per_engine_max_jobs 4`。再加入显式选择 `param_*` 的证明和逐性质状态导出命令；该部分 Tcl 语法必须用本机 JG 基线试跑确认，确认后文件冻结并取 hash。超出 JG 正常结束时间的进程守护超时固定为 2100 秒，触发时记工具异常，不能算限时无反例。

## 搜索与执行

1. **基线校准**：冻结当前源码 Git revision、子模块 revision、参数快照、`param_*` 性质清单和辅助 Verilog 文件；在 `verilog/coherence-param-tuning/runs/<hash>/` 生成 SV、做 JG `analyze/elaborate`，确认只对 `param_*` 执行的证明命令能启动、输出逐性质状态，并检查一个已知反例/有效性质可被识别。此步不声称新配置可行。
2. **生成候选**：枚举有限笛卡尔积；按下述目标排序。先做轻量范围/幂次过滤，Scala `require` 是结构约束的最终判定。相同参数快照只跑一次。
3. **逐候选运行**：为每组参数建立 `verilog/coherence-param-tuning/runs/<hash>/`，写入 `candidate.json`，让 Mill/Chisel 直接把 `VerifyTop.sv` 生成到此目录；JG 工程、日志和反例也留在同一候选目录。优先直接分析原始 SV；若 JG 确需后处理，只做经基线验证的语法修复，并在前后核对该候选按参数预期的 `param_*` 断言，不能沿用会注释断言的 `set_verify.py` 行为。固定辅助 Verilog 从 `verilog/coherence-param-tuning/` 引入，禁止改写共享脚本、覆盖既有 SV 或复制到其他 JG 工程。首版串行运行，避免 Mill/JG 共享状态冲突。
4. **固定 JG 配置与判定**：每个候选使用上述同一 Tcl 内容/hash，不通过 JSON、环境变量或 `setup.sh` 改配置。只有生成、JG `analyze/elaborate` 成功、目标性质清单与该候选的预期清单一致、证明任务实际运行到全部目标性质完成或到固定 proof time limit，且所有目标性质均无 `cex`，才标 `feasible_proved` 或 `bounded_no_cex`。`cex` 为不可行；编译失败、许可/工具错误、输出缺失、外部进程超时、性质缺失或无法解析状态均为独立终态，不得归入可行。保存原生日志、反例、逐性质状态及覆盖结果。
5. **停止与复核**：只有排序在前的候选均已明确判为静态无效、编译失败或出现反例，首个可行候选才是该离散空间的最小者；重新在干净目录运行该候选并核对 hash、性质数量与判定。若较小候选有工具失败或状态不明，先重试/排查，仍无法判定则只能报告“暂定最优”。若穷尽空间仍没有可行项，报告“未找到”；若达到候选上限或人为中断，仅报告“当前最优”，不声称全局最小。保留所有已尝试候选及终态。

排序目标先最小化总数据容量 `2*l1.ways*l1.sets*l1.blockBytes + 2*l2.ways*l2.sets*l2.blockBytes + l3.ways*l3.sets*l3.blockBytes`，再依次最小化总 MSHR 数 `2*l1.mshrs + 2*l2.mshrs + l3.mshrs`、三层 channelBytes 之和、RAM beatBytes，最后按完整参数元组打破并列。这是明确的**字典序定义**，不假定“大参数可行则小参数也可行”，因此不用二分或只调单维的贪心法。若用户更看重其他面积因素，应在实验开始前改定目标并重跑。

## 产物、检查与实施顺序

每个 `verilog/coherence-param-tuning/runs/<hash>/` 至少保存 `candidate.json`、生成日志、SV、固定 Tcl 的 hash、JG 原始日志、逐条 `param_*` 状态/覆盖结果和 `result.json`；总表记录候选总数、静态拒绝数、编译失败数、反例数、限时无反例数、工具失败数、未运行数、排序名次和最终选中项。结果必须区分 `feasible_proved` 与 `bounded_no_cex`，并记录性质/假设版本、源代码与子模块 revision、工具版本、固定资源上限和运行时长。

| 工作包 | 独立改动范围与完成条件 | 依赖 / 同步点 |
| --- | --- | --- |
| A：参数化顶层 | `VerifyTop.scala` 从候选快照取值、派生地址位宽与监测维度、加静态检查，禁用原有性质/假设；基线配置可生成 SV | 先约定 JSON 字段与默认值；和 B 同时做，但共同编辑 `VerifyTop.scala` 前划清参数段与性质段边界 |
| B：参数性质 | 按上表实现 `param_*` 断言、必要的只读监测点与覆盖检查；基线 JG 能列出预期性质且不含旧性质 | 依赖 A 的参数接口；与 A 合并后先核对基线 property 生成模板和有效触发 |
| C：固定 JG 与搜索 | JSON 枚举/排序、直接输出到目标 Verilog 目录、固定 Tcl、逐候选隔离、JG 状态解析与证据保存 | 可与 A/B 并行；只消费共同约定的候选字段和 `key=value` 生成命令；JG 状态接口须由基线实测确认 |

合并顺序：先固定配置与结果字段契约，合入 A、B 并完成基线的 SV/JG 检查，再合入 C，最后做单候选、已知编译失败、已知参数断言反例和两候选排序的端到端检查。A/B 的主要冲突点是 `VerifyTop.scala`，在基线产物与按参数预期的 `param_*` 清单一致后同步。实施时经 `rtk codex-run` 调用 Mill、Python 与 JG；不要在文档编写阶段运行耗时 JG。此方案尚未验证具体 JG Tcl 状态查询语法、许可可用性或任何候选的可行性，这些由基线校准给出实测结论。
