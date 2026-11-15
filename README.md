# 自动调参

本目录是独立 Git 仓库。根目录保留原搜索脚本、配置和测试；Chisel 示例及其依赖复制到 `param_tuning_example/`，全部作为普通源码管理，不使用 Git 子模块。原始项目未改动。

```text
param_tuning/
├── search.json             # 参数范围
├── search.py               # 静态约束、有序枚举及汇总
├── runner.py               # 生成、JG 验证及独立复核
├── test_search.py
├── test_runner.py
├── SOURCE_PROVENANCE.json  # 导入前各仓库的版本和本地修改记录
├── param_tuning_example/   # Chisel 工程和已展开的依赖源码
└── verilog/                # 固定 Tcl、五个辅助文件；运行后在此保存证据
```

运行需要 Python 3.9+、Git、`codex-run`、JDK、Mill 和 JasperGold。执行器沿用本机 `~/mill` 启动器，示例固定 Mill 0.11.5、Scala 2.13.15 和 Chisel 6.6.0；构建/JG 通过 `codex-run` 加载工具链环境。Python 搜索和单元测试只依赖标准库。根目录调参流程无需 `make init`、`git submodule update` 或重新应用原补丁。

示例工程保持原有文件内容（仅移除 `.git` 和 `.gitmodules`）。其中 `Makefile`、`AutoVerify`、`set_verify.py` 和上游文档保留旧子模块流程或旧环境路径；调参请使用根目录 `runner.py`，它调用 `ParamVerifyTop`。示例内 `param_tuning.md` 是早期设计档案，当前行为以本 README 和实现为准。

版本证据中的 `source_state` 和兼容字段 `submodule_revisions` 现在记录父仓库版本及各普通源码目录的 `tree_sha256`，覆盖暂存、未暂存和未忽略的新文件，排除构建产物；没有首次提交时版本明确标为 `uncommitted`。改变源码仍会阻止恢复或复核沿用旧输入。`SOURCE_PROVENANCE.json` 保留导入前 7 个仓库的原始版本；副本中的 `.gitmodules` 随 Git 元数据一起移除。

`search.json` 是搜索空间的唯一配置入口。未列出的参数取 `search.py` 中的基线值。配置仅接受 16 个已知参数键、整数闭区间和 `search.max_candidates`；`0` 表示不限。当前配置有 240,518,168,576 个原始组合，其中 24,482,115 项满足静态约束。排序依次为总数据容量、总 MSHR、三层 channelBytes 之和、完整参数元组。`ram.width` 表示 RAM 地址位宽；RAM `beatBytes=1` 与其他未开放参数保持专属 `VerifyTop.scala` 的值。

范围仍严格按 `min + n*step <= max` 解释。L3 sets 的下限从 4 改为 2 后，`step=4` 只包含 2、6、10…，排除了已知组合的 4，也排除了所有大于 2 的 2 的幂；因此当前使用 `step=2`。

从仓库根目录运行：

```bash
python3 search.py list --limit 20
python3 search.py prepare
python3 search.py summary
python3 runner.py
python3 runner.py --recheck
python3 runner.py --search
python3 -m unittest discover -s .
```

搜索器先过滤每个参数的取值，再按 RAM width 和 L2 sets 划分互不重叠的空间，传播 `4*sets <= 2**ram.width` 和 `l3.sets > l2.sets`。后者要求 L3 的组索引空间严格大于 L2；增加 L3 ways 不能代替该条件，各层可访问的 RAM 地址位宽仍相同。Scala 参数验证顶层也执行同一 `require`。每个空间用最小堆枚举，最后合并有序流；输出顺序与“穷举原空间、静态过滤、完整排序”一致，不假定形式化结果单调。其余静态约束保留 ways≥2、sets≥2 且为 2 的幂、MSHR 下限 4/4/6、RAM width 为 5..32；这些是当前验证契约，不声称更小的 MSHR 在硬件上必然不可用。`ParamVerifyTop` 固定 blockBytes=2、channelBytes=1，搜索器也提前拒绝其他值。

两个 L2 还各自要求 `param_cover_miss__l2_0/1` 到达：实际有效的 channel-A 请求因目录未命中而分配 MSHR。该覆盖绑定主流水线的 `mshr_alloc_s3.valid`、`task.fromA` 与 `!dirResult.hit`，不能用参数大小或普通请求握手代替 miss 证据。新增性质后的默认证据目录为 `verilog/runs-l3-space/<hash>/`；旧 `runs/` 原样保留，旧性质结果不自动沿用。`search.py` 与 `runner.py` 均支持 `--runs`；执行器要求它直接位于 `verilog` 下，以保持固定 Tcl 的相对路径。

`rank` 是静态有效空间中的名次；`total` 保留原始空间分母，`eligible_total` 是静态有效数量，`counts.static_reject` 记录批量剪枝数量，`attempted` 只计算实际已有结果的有效候选。`max_candidates` 只限制有效候选的检查数；静态剪枝不占预算。`prepare` 保存带配置范围、规则源码哈希、有效域及计数的 `runs-l3-space/static-pruning/<hash>.json`，以及待运行候选的 `candidate.json`，不为数千亿无效项建立目录。已有证据不覆盖。

执行器须在待运行候选目录写入 `result.json`，其 `candidate` 必须与快照完全相同；`status` 取 `static_reject`、`compile_failed`、`cex`、`tool_failed`、`property_missing`、`unknown`、`feasible_proved`、`bounded_no_cex` 之一。工具失败或未知状态会阻止搜索越过该候选。`--search` 连续验证下一项，自动独立复核首个可行项，在得到 `minimum`、空间耗尽、候选预算耗尽或未知/工具失败时停止，并更新 `verilog/search_summary.json`；默认仍只处理一项。

可行结果还须带 `sv_sha256`、`tcl_sha256`、`source_revision`、`submodule_revisions`、`property_version`、`assumption_version`、`tool_versions`、`generate_log`、`jg_log`、`elapsed_seconds`，以及 `expected_properties`/`properties` 和 `expected_coverage`/`coverage`。性质名按方案中的 `param_*` 族命名，展开项用 `__` 后缀；每项状态为 `proved` 或 `bounded_no_cex`。覆盖项须记录 `reached: true`，或 `reached: false` 和非空 `reason`。搜索器拒绝缺失清单和不完整记录，不从进程退出码推断可行性。

参数化生成入口为 `param_tuning_example/src/test/scala/coupledL2Verification/VerifyTop.scala` 中的 `ParamVerifyTop`，它直接写入候选目录；不调用会改写 `set_verify.py` 的 `AutoVerify`。`runner.py` 每次只处理排序中下一个候选，保存 Mill/JG 原始日志和逐条状态。`verify.tcl` 固定在 `verilog`；已存在的候选 SV/结果不覆盖。`property_missing`、`tool_failed` 和 `unknown` 阻止搜索跨越该候选。

完整证明可能达到固定的 1800 秒 JG 窗口。后台运行单个候选并查看进度：

```bash
nohup python3 runner.py --search > verilog/runner.log 2>&1 < /dev/null &
tail -f verilog/runner.log
```

同一候选运行期间不要再次启动 `runner.py`，以免共享 Mill/JG 工作目录冲突。

执行器在开始生成前保存 `run_metadata.json`，成功生成后加入 SV 哈希。中断后，`--resume-generated` 继续尚未开始的 JG 运行；`--finalize-existing` 解析已经完成的 JG 证据。两者均要求保存的候选、源码/子模块状态、Tcl、辅助文件及 SV 哈希与当前输入完全匹配。没有生成元数据的旧目录需要人工审计，不能自动恢复。畸形/重复状态行、未处理性质或 JG 非零退出不会产生可行结果。

首次选中候选后，汇总状态为 `pending_recheck`。`--recheck` 在同级 `<hash>-recheck` 新目录重新生成 SV 并执行固定证明窗口；源文件、SV、Tcl 和辅助文件哈希一致且复核也可行后，汇总才报告 `minimum`。`bounded_no_cex` 保留窗口内未定的断言状态，不表示全证明。

2026-10-03，原工程补充 L3 组索引空间约束后的历史结果如下；本次迁移不把旧结果作为新仓库的验证证据：

| 层级 | ways | sets | mshrs |
| --- | ---: | ---: | ---: |
| L1（两个实例） | 2 | 2 | 4 |
| L2（两个实例） | 2 | 2 | 4 |
| L3 | 2 | 4 | 6 |

`ram.width=5`；固定 `blockBytes=2`、`channelBytes=1`、RAM `beatBytes=1`。排序目标总数据容量为 48 字节，总 MSHR 为 22。两轮性质清单均严格匹配 118 条断言、25 条覆盖，使用固定 1800 秒窗口；首跑为 85 条 `proved`、33 条 `bounded_no_cex`，独立复核为 86 条 `proved`、32 条 `bounded_no_cex`。两轮 25 条覆盖全部到达，包括两个新增 L2 miss 覆盖（均在 10 个周期内到达），无反例。独立重新生成的 SV、源码、子模块状态、Tcl 与辅助文件哈希均与首跑一致；逐条 JG 正式状态与保存结果已再次核对。

历史证据仍保留在原工程 `verilog/coherence-param-tuning/runs-l3-space/8e86f2701114132d2ebc2a39c1cc42d856df157d0674e8b3509f8bd293733ef6/` 及同级 `-recheck` 目录，本仓库不复制旧运行目录或旧汇总。新仓库使用自己的 `verilog/runs-l3-space/` 和 `verilog/search_summary.json`，首次运行前状态应为 `incomplete`。此前的 40 字节组合不满足 `l3.sets > l2.sets`，不属于有效空间。`bounded_no_cex` 的最小性仅适用于对应静态约束、排序及固定窗口判定，不表示全部断言获证。

迁移后 15 项单元测试通过，并从暂存区导出到原目录之外，验证了测试、搜索 CLI 和执行器帮助入口。新增测试检查普通依赖目录的源码哈希能感知暂存、未暂存和新文件变化，并排除忽略的构建输出。 复制后的 Chisel 工程已实际生成 SV，JG 分析/展开成功，性质清单严格匹配 118 条断言和 25 条覆盖；完整命令、日志和检查结果在 `verilog/migration-smoke/`（忽略目录）。该检查未执行证明，未产生新的可行性或最小性结论。

原工程的 14 项单元测试覆盖剪枝与完整穷举排序一致、RAM/sets/L2-L3 联动、空空间、候选预算、失败阻塞、连续搜索的复核分支，以及缺少任一 L2 miss 覆盖时拒绝判为可行。另直接调用 Scala 生成入口验证 `l2.sets=l3.sets=2`：明确触发层级约束、生成失败且没有输出 SV，证据保留在原工程 `verilog/coherence-param-tuning/runs-l3-space/scala-guard-check/`。
