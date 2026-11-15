package coupledL2Verification

import chisel3._
import circt.stage.ChiselStage
import chisel3.ltl._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2._
import coupledL2.MetaData._
import coupledL2.tl2tl.{Slice => L2Slice, _}
import coupledL2AsL1._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import org.chipsalliance.cde.config._
import utility._

import java.io.File


object baseConfig {
  def apply(maxHartIdBits: Int) = {
    new Config((_, _, _) => {
      case MaxHartIdBits => maxHartIdBits
    })
  }
}

case class TuningParams(
  l1Ways: Int = 2, l1Sets: Int = 2, l1Mshrs: Int = 4,
  l2Ways: Int = 2, l2Sets: Int = 4, l2Mshrs: Int = 4,
  l3Ways: Int = 2, l3Sets: Int = 4, l3Mshrs: Int = 6,
  ramWidth: Int = 5
) {
  private def power2(x: Int): Boolean = x > 0 && (x & (x - 1)) == 0
  require(ramWidth >= 5 && ramWidth <= 32, "ram.width must be in 5..32")
  for ((layer, ways, sets, mshrs, minimum) <- Seq(
    ("l1", l1Ways, l1Sets, l1Mshrs, 4),
    ("l2", l2Ways, l2Sets, l2Mshrs, 4),
    ("l3", l3Ways, l3Sets, l3Mshrs, 6)
  )) {
    require(ways >= 2, s"$layer.ways must be >= 2")
    require(sets >= 2 && power2(sets), s"$layer.sets must be a power of two >= 2")
    require(mshrs >= minimum, s"$layer.mshrs must be >= $minimum")
    require(ramWidth - log2Ceil(sets) - 1 > 0, s"$layer.tagBits must be positive")
    require((BigInt(1) << ramWidth) >= 4 * sets, s"ram.width cannot cover $layer sets and two tags")
  }
}

class VerifyTop(tuning: TuningParams = TuningParams(), parameterMode: Boolean = false)
               (implicit p: Parameters) extends LazyModule {
  if (parameterMode) require(tuning.l3Sets > tuning.l2Sets,
    "l3.sets must be > l2.sets (larger L3 set-index space)")

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL2 = 2

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }
  val l0_nodes = (0 until nrL2).map(i => createClientNode(s"L0_$i", 32))

  val coupledL2AsL1 = (0 until nrL2).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"L1d_$i",
      ways = tuning.l1Ways,
      sets = tuning.l1Sets,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = tuning.l1Mshrs,
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      // echoField = Seq(DirtyField()),
      hartId = i,
      prefetch = Seq(InputAsPrefectchParam())
    )
    case huancun.BankBitsKey => 0
    case LogUtilsOptionsKey => LogUtilsOptions(
      false,
      here(L2ParamKey).enablePerf,
      here(L2ParamKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(L2ParamKey).enablePerf && !here(L2ParamKey).FPGAPlatform,
      here(L2ParamKey).enableRollingDB && !here(L2ParamKey).FPGAPlatform,
      XSPerfLevel.withName("VERBOSE"),
      i
    )
  })))
  )
  val l1d_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alter((_, here, _) => {
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = tuning.l2Ways,
      sets = tuning.l2Sets,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = tuning.l2Mshrs,
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      hartId = i,
    )
    case huancun.BankBitsKey => 0
    case LogUtilsOptionsKey => LogUtilsOptions(
      false,
      here(L2ParamKey).enablePerf,
      here(L2ParamKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(L2ParamKey).enablePerf && !here(L2ParamKey).FPGAPlatform,
      here(L2ParamKey).enableRollingDB && !here(L2ParamKey).FPGAPlatform,
      XSPerfLevel.withName("VERBOSE"),
      i
    )
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alter((_, here, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = tuning.l3Ways,
      sets = tuning.l3Sets,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      mshrs = tuning.l3Mshrs,
      inclusive = false,
      clientCaches = (0 until nrL2).map(_ =>
        CacheParameters(
          name = s"l2",
          sets = tuning.l2Sets,
          ways = tuning.l2Ways + 2,
          blockGranularity = log2Ceil(tuning.l2Sets),
          blockBytes = if (parameterMode) 2 else 64
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true
    )
    case LogUtilsOptionsKey => LogUtilsOptions(
      here(HCCacheParamsKey).enableDebug,
      here(HCCacheParamsKey).enablePerf,
      here(HCCacheParamsKey).FPGAPlatform
    )
    case PerfCounterOptionsKey => PerfCounterOptions(
      here(HCCacheParamsKey).enablePerf && !here(HCCacheParamsKey).FPGAPlatform,
      false,
      XSPerfLevel.withName("VERBOSE"),
      0
    )
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, (BigInt(1) << tuning.ramWidth) - 1), beatBytes = 1))

  l0_nodes.zip(l1d_nodes) map {
    case (l0, l1d) => l1d := l0
  }

  l1d_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := 
        TLLogger(s"L2_L1[${i}].C[0]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
        TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case(l2, i) => xbar := 
      TLLogger(s"L3_L2[${i}]", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) := 
      TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3", !cacheParams.FPGAPlatform && cacheParams.enableTLLog) :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    coupledL2AsL1.foreach {
      l1 => {
        l1.module.io.debugTopDown <> DontCare
        l1.module.io.hartId := DontCare
        l1.module.io.pfCtrlFromCore := DontCare
        l1.module.io.l2_tlb_req <> DontCare
      }
    }

    coupledL2.foreach {
      l2 => {
        l2.module.io.debugTopDown <> DontCare
        l2.module.io.hartId := DontCare
        l2.module.io.pfCtrlFromCore := DontCare
        l2.module.io.l2_tlb_req <> DontCare
      }
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U

    val io = IO(Vec(nrL2, new Bundle() {
      // Input signals for formal verification
      val inputAddr = Input(UInt(ram.node.in.head._2.bundle.addressBits.W))
      val inputNeedT = Input(Bool())
      val inputRequestType = Input(Bool()) // 0 Acquire, 1 Release
    }))

    coupledL2AsL1.zipWithIndex.foreach{
      case (node, i) =>
        if (!parameterMode) assume(io(i).inputAddr === 0.U)
        node.module.io_inputAddr := io(i).inputAddr
        node.module.io_inputNeedT := io(i).inputNeedT
        node.module.io_requestType := io(i).inputRequestType
    }

    if (!parameterMode) {
      coupledL2(0).module.slices.head match {
        case tlSlice: L2Slice =>
          val dir_resetFinish = BoringUtils.bore(tlSlice.directory.resetFinish)
          assume(verify_timer < 200.U || dir_resetFinish)
      }
    }

    if (!parameterMode) {
    val timer = 2000
    coupledL2.foreach { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          // val w_rprobeacklast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_rprobeacklast)
          // astRelaxedLiveness(!w_rprobeacklast, w_rprobeacklast, timer)
          // val w_pprobeacklast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_pprobeacklast)
          // astRelaxedLiveness(!w_pprobeacklast, w_pprobeacklast, timer)
          // val w_grantlast = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_grantlast)
          // astRelaxedLiveness(!w_grantlast, w_grantlast, timer)
          // val w_releaseack = BoringUtils.bore(tlSlice.mshrCtl.mshrs.head.state.w_releaseack)
          // astRelaxedLiveness(!w_releaseack, w_releaseack, timer)
      }
    }

    // ========== Mutual, Inclusive, Consistency Properties ==========
    
    // Address parsing functions
    val l1_offsetBits = 1
    val l1_bankBits = 0
    val l1_setBits = 1
    val l1_tagBits = 3

    def parseL1Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l1_offsetBits + l1_bankBits)
      val tag = set >> l1_setBits
      (tag(l1_tagBits - 1, 0), set(l1_setBits - 1, 0), offset(l1_offsetBits - 1, 0))
    }

    val l2_offsetBits = 1
    val l2_bankBits = 0
    val l2_setBits = 2
    val l2_tagBits = 2

    def parseL2Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l2_offsetBits + l2_bankBits)
      val tag = set >> l2_setBits
      (tag(l2_tagBits - 1, 0), set(l2_setBits - 1, 0), offset(l2_offsetBits - 1, 0))
    }

    val l3_offsetBits = 1
    val l3_bankBits = 0
    val l3_setBits = 2
    val l3_tagBits = 2

    def parseL3Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l3_offsetBits + l3_bankBits)
      val tag = set >> l3_setBits
      (tag(l3_tagBits - 1, 0), set(l3_setBits - 1, 0), offset(l3_offsetBits - 1, 0))
    }

    // Get arrays from L1 (CoupledL2AsL1) and L2 (CoupledL2)
    val l1_stateArrays = coupledL2AsL1.map { l1 =>
      val slice = l1.module.slices.head.asInstanceOf[L1Slice]
      BoringUtils.bore(slice.directoryTest.io.stateArray)
    }

    val l1_tagArrays = coupledL2AsL1.map { l1 =>
      val slice = l1.module.slices.head.asInstanceOf[L1Slice]
      BoringUtils.bore(slice.directoryTest.io.tagArray)
    }

    val l2_stateArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.directoryTest.io.stateArray)
      }
    }

    val l2_tagArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.directoryTest.io.tagArray)
      }
    }

    val l2_dataArrays = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          BoringUtils.bore(tlSlice.dataStorageTest.io.dataArray)
      }
    }

    // Get L3 arrays from directorySync
    val l3_selfStateArrays: Vec[Vec[UInt]] = {
      val slice = l3.module.slices.head
      slice.directorySync.map { sync =>
        val bored = BoringUtils.bore(sync.io.selfDirArray)
        VecInit(bored.toSeq.map(set => VecInit(set.toSeq.map(_.state))))
      }.getOrElse(VecInit(Seq.fill(4)(VecInit(Seq.fill(2)(0.U(2.W))))))
    }

    val l3_selfTagArrays: Vec[Vec[UInt]] = {
      val slice = l3.module.slices.head
      slice.directorySync.map { sync =>
        val bored = BoringUtils.bore(sync.io.selfTagArray)
        VecInit(bored.toSeq.map(set => VecInit(set.toSeq: Seq[UInt])))
      }.getOrElse(VecInit(Seq.fill(4)(VecInit(Seq.fill(2)(0.U(2.W))))))
    }

    // Get MSHR signals for property patches
    val mshrs = 4
    val l3_mshrs = 6

    class MSHRSignal(setBits: Int) extends Bundle {
      val status_bits_set = UInt(setBits.W)
      val status_valid = Bool()
    }

    val L1_MSHR_signals = coupledL2AsL1.map { l1 =>
      l1.module.slices.head match {
        case tlSlice: L1Slice =>
          (0 until mshrs).map { i =>
            val mshr_sig = Wire(new MSHRSignal(l1_setBits))
            mshr_sig.status_bits_set := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.set)
            mshr_sig.status_valid := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.valid)
            mshr_sig
          }
      }
    }

    val L2_MSHR_signals = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice =>
          (0 until mshrs).map { i =>
            val mshr_sig = Wire(new MSHRSignal(l2_setBits))
            mshr_sig.status_bits_set := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.bits.set)
            mshr_sig.status_valid := BoringUtils.bore(tlSlice.mshrCtl.mshrs(i).io.status.valid)
            mshr_sig
          }
      }
    }

    // Get L3 MSHR signals for L2-L3 mutual exclusivity patch
    val L3_MSHR_signals = l3.module.slices.head.ms.map { mshr =>
      val mshr_sig = Wire(new MSHRSignal(l3_setBits))
      mshr_sig.status_bits_set := BoringUtils.bore(mshr.io.status.bits.set)
      mshr_sig.status_valid := BoringUtils.bore(mshr.io.status.valid)
      mshr_sig
    }

    val l1_MSHR_prop_patch = coupledL2AsL1.indices.map { indx =>
      Cat(L1_MSHR_signals(indx).map { m =>
        m.status_bits_set === 0.U &&
        m.status_valid === true.B
      }).orR
    }

    val l2_MSHR_prop_patch = coupledL2.indices.map { indx =>
      Cat(L2_MSHR_signals(indx).map { m =>
        m.status_bits_set === 0.U &&
        m.status_valid === true.B
      }).orR
    }

    val l3_MSHR_prop_patch = Cat(L3_MSHR_signals.map { m =>
      m.status_bits_set === 0.U && 
      m.status_valid === true.B
    }).orR

    val l1_reqarb_prop_patch_s1 = coupledL2AsL1.map { l1 =>
      l1.module.slices.head match {
        case tlSlice: L1Slice => 
          val req_arb_vaild = BoringUtils.bore(tlSlice.reqArb.task_s1.valid)
          val req_arb_set = BoringUtils.bore(tlSlice.reqArb.task_s1.bits.set)
          req_arb_vaild && req_arb_set === 0.U
        case _ => false.B
      }
    }
    val l1_mainpipe_prop_patch_s2 = coupledL2AsL1.map { l1 =>
      l1.module.slices.head match {
        case tlSlice: L1Slice => 
          val mainpipe_s2_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s2.valid)
          val mainpipe_s2_set = BoringUtils.bore(tlSlice.mainPipe.task_s2.bits.set)
          mainpipe_s2_vaild && mainpipe_s2_set === 0.U
        case _ => false.B
      }
    }
    val l1_mainpipe_prop_patch_s3 = coupledL2AsL1.map { l1 =>
      l1.module.slices.head match {
        case tlSlice: L1Slice => 
          val mainpipe_s3_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s3.valid)
          val mainpipe_s3_set = BoringUtils.bore(tlSlice.mainPipe.task_s3.bits.set)
          mainpipe_s3_vaild && mainpipe_s3_set === 0.U
        case _ => false.B
      }
    }

    // l2l3mutual: 补l2 mainpipe task_s1(request_arb)/s2/s3(mainpipe) set=0
    val l2_reqarb_prop_patch_s1 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val req_arb_vaild = BoringUtils.bore(tlSlice.reqArb.task_s1.valid)
          val req_arb_set = BoringUtils.bore(tlSlice.reqArb.task_s1.bits.set)
          req_arb_vaild && req_arb_set === 0.U
        case _ => false.B
      }
    }
    val l2_mainpipe_prop_patch_s2 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val mainpipe_s2_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s2.valid)
          val mainpipe_s2_set = BoringUtils.bore(tlSlice.mainPipe.task_s2.bits.set)
          mainpipe_s2_vaild && mainpipe_s2_set === 0.U
        case _ => false.B
      }
    }
    val l2_mainpipe_prop_patch_s3 = coupledL2.map { l2 =>
      l2.module.slices.head match {
        case tlSlice: L2Slice => 
          val mainpipe_s3_vaild = BoringUtils.bore(tlSlice.mainPipe.task_s3.valid)
          val mainpipe_s3_set = BoringUtils.bore(tlSlice.mainPipe.task_s3.bits.set)
          mainpipe_s3_vaild && mainpipe_s3_set === 0.U
        case _ => false.B
      }
    }
    // l2.module.slices.head.reqarb.mshr_task_s1.valid && ???.bits.set === 0.U
    // l2.module.slices.head.reqarb.task_s1.valid && ???.bits.set === 0.U
    // l2.module.slices.mainpipe.task_s2.valid && ???.bits.set === 0.U
    // l2.module.slices.mainpipe.task_s3.valid && ???.bits.set === 0.U

    // Mutual exclusivity property (L2-L2)
    def l2_mutual(addr: UInt, state1: UInt, state2: UInt): Unit = {
      if(state1 == INVALID || state2 == INVALID) {
        l2_mutual_with_invalid(addr, state1, state2)
        return
      }

      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      fvAssert(!(hit0 && l2_stateArrays(0)(set)(way0) === state1 &&
                 hit1 && l2_stateArrays(1)(set)(way1) === state2))
    }

    def l2_mutual_with_invalid(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val hit0 = Mux(state1 === INVALID,
        !l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
          case (tag0, state0) =>
            tag === tag0 && state0 =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
          case (tag0, state0) =>
            tag === tag0 && state0 === state1
        }.reduce(_ || _)
      )

      val hit1 = Mux(state2 === INVALID,
        !l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
          case (tag1, state1_actual) =>
            tag === tag1 && state1_actual =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
          case (tag1, state1_actual) =>
            tag === tag1 && state1_actual === state2
        }.reduce(_ || _)
      )

      fvAssert(!(hit0 && hit1))
    }

    // Mutual exclusivity property (L1-L2)
    def l1_l2_mutual(addr: UInt, l1_state: UInt, l2_state: UInt): Unit = {
      if(l1_state == INVALID || l2_state == INVALID) {
        l1_l2_mutual_with_invalid(addr, l1_state, l2_state)
        return
      }

      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      
      val l1_hit_vec = l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
        case (tag, state) =>
          tag === l1_tag && state =/= INVALID
      }
      val l1_hit = l1_hit_vec.reduce(_ || _)
      val l1_way = OHToUInt(l1_hit_vec)

      val l2_hit_vec = l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val mutualViolation =
        l1_hit && l1_stateArrays(0)(l1_set)(l1_way) === l1_state &&
        l2_hit && l2_stateArrays(0)(l2_set)(l2_way) === l2_state

      fvAssert(l1_MSHR_prop_patch(0) || 
               l2_MSHR_prop_patch(0) ||
               l1_reqarb_prop_patch_s1(0) ||
               l1_mainpipe_prop_patch_s2(0) ||
               l1_mainpipe_prop_patch_s3(0) ||
               l2_reqarb_prop_patch_s1(0) ||
               l2_mainpipe_prop_patch_s2(0) ||
               l2_mainpipe_prop_patch_s3(0) ||
               !mutualViolation)
    }

    def l1_l2_mutual_with_invalid(addr: UInt, l1_state: UInt, l2_state: UInt): Unit = {
      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)

      val l1_hit = Mux(l1_state === INVALID,
        !l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
          case (tag1, state1) =>
            tag1 === l1_tag && state1 =/= INVALID
        }.reduce(_ || _)
      ,
        l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
          case (tag1, state1) =>
            tag1 === l1_tag && state1 === l1_state
        }.reduce(_ || _)
      )

      val l2_hit = Mux(l2_state === INVALID,
        !l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 === l2_state
        }.reduce(_ || _)
      )

      fvAssert(l1_MSHR_prop_patch(0) || 
               l2_MSHR_prop_patch(0) ||
               l1_reqarb_prop_patch_s1(0) ||
               l1_mainpipe_prop_patch_s2(0) ||
               l1_mainpipe_prop_patch_s3(0) ||
               l2_reqarb_prop_patch_s1(0) ||
               l2_mainpipe_prop_patch_s2(0) ||
               l2_mainpipe_prop_patch_s3(0) ||
               !(l1_hit && l2_hit))
    }

    def l2_mutual_specs(): Unit = {
      l2_mutual(0.U(32.W), TIP, TIP)
      l2_mutual(0.U(32.W), TIP, BRANCH)
    }

    // L2-L3 mutual exclusivity property
    def l2_l3_mutual(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      if(l2_state == INVALID || l3_state == INVALID) {
        l2_l3_mutual_with_invalid(addr, l2_state, l3_state, l2_idx)
        return
      }

      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      val (l3_tag, l3_set, l3_offset) = parseL3Address(addr)

      val l2_hit_vec = l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val l3_hit_vec = l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
        case (tag, state) =>
          tag === l3_tag && state =/= INVALID
      }
      val l3_hit = l3_hit_vec.reduce(_ || _)
      val l3_way = OHToUInt(l3_hit_vec)

      val mutualViolation =
        l2_hit && l2_stateArrays(l2_idx)(l2_set)(l2_way) === l2_state &&
        l3_hit && l3_selfStateArrays(l3_set)(l3_way) === l3_state

      fvAssert(
        !mutualViolation || 
        l3_MSHR_prop_patch || 
        l2_MSHR_prop_patch(l2_idx) ||
        l2_reqarb_prop_patch_s1(l2_idx) || 
        l2_mainpipe_prop_patch_s2(l2_idx) ||
        l2_mainpipe_prop_patch_s3(l2_idx)
      )
    }

    def l2_l3_mutual_with_invalid(addr: UInt, l2_state: UInt, l3_state: UInt, l2_idx: Int = 0): Unit = {
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      val (l3_tag, l3_set, l3_offset) = parseL3Address(addr)

      val l2_hit = Mux(l2_state === INVALID,
        !l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 =/= INVALID
        }.reduce(_ || _)
      ,
        l2_tagArrays(l2_idx)(l2_set).zip(l2_stateArrays(l2_idx)(l2_set)).map {
          case (tag2, state2) =>
            tag2 === l2_tag && state2 === l2_state
        }.reduce(_ || _)
      )

      val l3_hit = Mux(l3_state === INVALID,
        !l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
          case (tag3, state3) =>
            tag3 === l3_tag && state3 =/= INVALID
        }.reduce(_ || _)
      ,
        l3_selfTagArrays(l3_set).zip(l3_selfStateArrays(l3_set)).map {
          case (tag3, state3) =>
            tag3 === l3_tag && state3 === l3_state
        }.reduce(_ || _)
      )

      fvAssert(
        !(l2_hit && l3_hit) || 
        l3_MSHR_prop_patch || 
        l2_MSHR_prop_patch(l2_idx) ||
        l2_reqarb_prop_patch_s1(l2_idx) || 
        l2_mainpipe_prop_patch_s2(l2_idx) ||
        l2_mainpipe_prop_patch_s3(l2_idx)
      )
    }

    def l2_l3_mutual_specs(): Unit = {
      // From the table, impossible combinations (marked as 0):
      // L3=TT, L2=TT
      l2_l3_mutual(0.U(32.W), TIP, TIP, 0)
      l2_l3_mutual(0.U(32.W), TIP, TIP, 1)
      // L3=TT, L2=T
      l2_l3_mutual(0.U(32.W), TRUNK, TIP, 0)
      l2_l3_mutual(0.U(32.W), TRUNK, TIP, 1)
      // // L3=T, L2=T
      // l2_l3_mutual(0.U(32.W), TRUNK, TRUNK, 0)
      // l2_l3_mutual(0.U(32.W), TRUNK, TRUNK, 1)
      // L3=T, L2=B
      l2_l3_mutual(0.U(32.W), BRANCH, TRUNK, 0)
      l2_l3_mutual(0.U(32.W), BRANCH, TRUNK, 1)
      // L3=T, L2=N
      l2_l3_mutual(0.U(32.W), INVALID, TRUNK, 0)
      l2_l3_mutual(0.U(32.W), INVALID, TRUNK, 1)
      // L3=B, L2=T
      l2_l3_mutual(0.U(32.W), TRUNK, BRANCH, 0)
      l2_l3_mutual(0.U(32.W), TRUNK, BRANCH, 1)
    }

    def l1l2_mutual_specs(): Unit = { 
      // L1-L2 mutual exclusivity (all impossible combinations from table)
      // L2=TT, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, TIP)
      // L2=TT, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, TIP)
      // L2=T, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, TRUNK)
      // L2=T, L1=B
      l1_l2_mutual(0.U(32.W), BRANCH, TRUNK)
      // L2=T, L1=N
      l1_l2_mutual(0.U(32.W), INVALID, TRUNK)
      // L2=B, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, BRANCH)
      // L2=B, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, BRANCH)
      // L2=N, L1=TT
      l1_l2_mutual(0.U(32.W), TIP, INVALID)
      // L2=N, L1=T
      l1_l2_mutual(0.U(32.W), TRUNK, INVALID)
      // L2=N, L1=B
      l1_l2_mutual(0.U(32.W), BRANCH, INVALID)
    }

    // Inclusive property
    def l1l2_inclusive(addr: UInt): Unit = {
      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      
      val l1_hit_vec = l1_tagArrays(0)(l1_set).zip(l1_stateArrays(0)(l1_set)).map {
        case (tag, state) =>
          tag === l1_tag && state =/= INVALID
      }
      val l1_hit = l1_hit_vec.reduce(_ || _)
      val l1_way = OHToUInt(l1_hit_vec)

      val l2_hit_vec = l2_tagArrays(0)(l2_set).zip(l2_stateArrays(0)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      val valid_line = l1_hit && l1_stateArrays(0)(l1_set)(l1_way) =/= INVALID

      // assume(verify_timer < 800.U || (l1_hit && l1_stateArrays(0)(l1_set)(l1_way) =/= INVALID))
      val t1: Sequence = verify_timer > 800.U
      val t2: Sequence = valid_line
      AssumeProperty(t1 |-> t2.eventually)

      fvAssert(!valid_line || 
               (l2_hit && l2_stateArrays(0)(l2_set)(l2_way) =/= INVALID) || 
               l2_MSHR_prop_patch(0))
    }

    def inclusive_spec(): Unit = {
      l1l2_inclusive(0.U(32.W))
    }

    // Consistency property
    def l2_consistency(addr: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArrays(0)(set).zip(l2_stateArrays(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArrays(1)(set).zip(l2_stateArrays(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      val arrayIdx0 = Cat(way0, set)
      val arrayIdx1 = Cat(way1, set)

      val valid_state = hit0 && l2_stateArrays(0)(set)(way0) === BRANCH && 
                        hit1 && l2_stateArrays(1)(set)(way1) === BRANCH

      fvAssert(!valid_state ||
               l2_dataArrays(0)(arrayIdx0) === l2_dataArrays(1)(arrayIdx1) || 
               l2_MSHR_prop_patch(0) || 
               l2_MSHR_prop_patch(1))
    }

    def consistency_spec(): Unit = {
      l2_consistency(0.U(32.W))
    }

    // Enable verification properties (comment out as needed)
    // l1l2_mutual_specs()
    // l2_mutual_specs()
    // l2_l3_mutual_specs()
    // inclusive_spec()
    consistency_spec()
    }

    if (parameterMode) {
      // 调参断言由 fvAssert 在 notChaos 时启用；每条覆盖分别寻找可达轨迹。
      def checkSlice(slice: L2Slice, layer: String, index: Int,
                     ways: Int, sets: Int, mshrs: Int): Unit = {
        val tagWrite = BoringUtils.bore(slice.directory.io.tagWReq)
        // 断言：有效 tag 写入的 way 编号必须在配置范围内。
        fvAssert(!tagWrite.valid || tagWrite.bits.way < ways.U,
          s"param_way_index__${layer}_$index")

        val tags = BoringUtils.bore(slice.directoryTest.io.tagArray)
        val states = BoringUtils.bore(slice.directoryTest.io.stateArray)
        val selectedSet = BoringUtils.bore(slice.sinkA.io.task.bits.set)
        val oldTags = RegNext(tags(tagWrite.bits.set))
        val wrote = RegNext(tagWrite.valid, false.B)
        val writtenSet = RegNext(tagWrite.bits.set)
        val writtenWay = RegNext(tagWrite.bits.way)
        // 断言：上一拍写入某个 way 后，同组其他 way 的 tag 保持不变。
        for (way <- 0 until ways) {
          fvAssert(!wrote || writtenWay === way.U ||
            tags(writtenSet)(way) === oldTags(way),
            s"param_way_isolation__${layer}_${index}_$way")
        }
        // 断言：当前观察组内，任意两个有效 way 的 tag 不得重复。
        for (left <- 0 until ways; right <- left + 1 until ways) {
          fvAssert(states(selectedSet)(left) === INVALID ||
            states(selectedSet)(right) === INVALID ||
            tags(selectedSet)(left) =/= tags(selectedSet)(right),
            s"param_unique_live_tag__${layer}_${index}_${left}_$right")
        }

        val inA = slice.sinkA.io.a
        val inValid = BoringUtils.bore(inA.valid)
        val inReady = BoringUtils.bore(inA.ready)
        val address = BoringUtils.bore(inA.bits.address)
        val taskValid = BoringUtils.bore(slice.sinkA.io.task.valid)
        val taskReady = BoringUtils.bore(slice.sinkA.io.task.ready)
        // 覆盖：每个组分别至少出现一次内部请求握手。
        for (set <- 0 until sets) {
          chisel3.ltl.CoverProperty(taskValid && taskReady && selectedSet === set.U,
            s"param_cover_set__${layer}_${index}_$set")
        }
        // 覆盖：当前观察组的 way 0 和 way 1 同时有效。
        chisel3.ltl.CoverProperty(
          states(selectedSet)(0) =/= INVALID && states(selectedSet)(1) =/= INVALID,
          s"param_cover_two_ways__${layer}_$index")
        val task = slice.sinkA.io.task
        val taskSet = BoringUtils.bore(task.bits.set)
        val taskTag = BoringUtils.bore(task.bits.tag)
        val taskOff = BoringUtils.bore(task.bits.off)
        val decodedA = !inValid || !inReady ||
          (taskSet === address(log2Ceil(sets), 1) &&
           taskTag === (address >> (log2Ceil(sets) + 1)).asUInt &&
           taskOff === address(0))
        // 断言：A 请求握手时，set/tag/offset 与地址按配置切分的结果一致。
        if (layer == "l1") {
          // L1 traffic enters through Input2Req, rather than the unused L0 A port.
          val req = slice.sinkA.io.prefetchReq.get
          val reqValid = BoringUtils.bore(req.valid)
          val reqReady = BoringUtils.bore(req.ready)
          val reqBits = BoringUtils.bore(req.bits)
          val inputReq = coupledL2AsL1(index).module.prefetcher.get.io.req
          val inputValid = BoringUtils.bore(inputReq.valid)
          val inputReady = BoringUtils.bore(inputReq.ready)
          val inputBits = BoringUtils.bore(inputReq.bits)
          // L1 还检查 Input2Req 地址解码及预取请求转内部任务；任务 offset 固定为 0。
          fvAssert(decodedA &&
            (!(reqValid && reqReady) ||
              (taskSet === reqBits.set && taskTag === reqBits.tag && taskOff === 0.U)) &&
            (!(inputValid && inputReady) ||
              (inputBits.set === io(index).inputAddr(log2Ceil(sets), 1) &&
               inputBits.tag === (io(index).inputAddr >> (log2Ceil(sets) + 1)).asUInt)),
            s"param_address_decode__${layer}_$index")
        } else {
          fvAssert(decodedA, s"param_address_decode__${layer}_$index")
        }

        val outA = slice.io.out.a
        val outValid = BoringUtils.bore(outA.valid)
        val outBits = BoringUtils.bore(outA.bits)
        val blockRequest = outValid &&
          (outBits.opcode === TLMessages.AcquireBlock ||
           outBits.opcode === TLMessages.AcquirePerm)
        // 断言：有效 AcquireBlock/AcquirePerm 请求按 2 字节块对齐。
        fvAssert(!blockRequest || outBits.address(0) === 0.U,
          s"param_block_boundary__${layer}_$index")
        // 断言：块获取请求 size=1，即传输大小为 2 字节。
        fvAssert(!blockRequest || outBits.size === 1.U,
          s"param_block_size__${layer}_$index")
        // 断言：块获取请求的单字节传输 mask 为 1。
        fvAssert(!blockRequest || outBits.mask === 1.U,
          s"param_beat_shape__${layer}_$index")

        val slots = (0 until mshrs).map { i =>
          (BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.valid),
           BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.bits.set),
           BoringUtils.bore(slice.mshrCtl.mshrs(i).io.status.bits.reqTag))
        }
        val occupied = PopCount(slots.map(_._1))
        // 断言：所观察的普通 MSHR 有效槽位数不超过配置数量。
        fvAssert(occupied <= mshrs.U, s"param_mshr_capacity__${layer}_$index")
        val alloc = BoringUtils.bore(slice.mshrCtl.io.fromMainPipe.mshr_alloc_s3.valid)
        val allocId = BoringUtils.bore(slice.mshrCtl.io.toMainPipe.mshr_alloc_ptr)
        // 断言：分配编号合法且目标槽位空闲；不检查事务最终完成。
        fvAssert(!alloc || (allocId < mshrs.U && !VecInit(slots.map(_._1))(allocId)),
          s"param_mshr_progress__${layer}_$index")
        if (layer == "l2") {
          val allocation = BoringUtils.bore(slice.mshrCtl.io.fromMainPipe.mshr_alloc_s3)
          // 覆盖：该 L2 实际为 channel-A 目录 miss 分配 MSHR；不限定 miss 成因。
          chisel3.ltl.CoverProperty(allocation.valid && allocation.bits.task.fromA &&
            !allocation.bits.dirResult.hit, s"param_cover_miss__l2_$index")
        }
        // 断言：两个有效 MSHR 不得同时跟踪相同的 set/tag。
        for (left <- 0 until mshrs; right <- left + 1 until mshrs) {
          val (validA, setA, tagA) = slots(left)
          val (validB, setB, tagB) = slots(right)
          fvAssert(!(validA && validB && setA === setB && tagA === tagB),
            s"param_mshr_unique__${layer}_${index}_${left}_$right")
        }
        // 覆盖：至少两个普通 MSHR 同时有效。
        chisel3.ltl.CoverProperty(occupied >= 2.U,
          s"param_cover_two_mshrs__${layer}_$index")

        val aValid = BoringUtils.bore(slice.io.out.a.valid)
        val aReady = BoringUtils.bore(slice.io.out.a.ready)
        val aBits = BoringUtils.bore(slice.io.out.a.bits).asUInt
        val stalled = RegNext(aValid && !aReady, false.B)
        val previous = RegNext(aBits)
        // 断言：A 通道上一拍 valid 且未 ready 时，下一拍 valid 和载荷保持不变。
        fvAssert(!stalled || (aValid && aBits === previous),
          s"param_beat_stable__${layer}_$index")
        val cValid = BoringUtils.bore(slice.io.out.c.valid)
        val cReady = BoringUtils.bore(slice.io.out.c.ready)
        val cBits = BoringUtils.bore(slice.io.out.c.bits)
        val cStalled = RegNext(cValid && !cReady, false.B)
        val cPrevious = RegNext(cBits.asUInt)
        // 断言：C 通道阻塞后，下一拍保持 valid 和载荷。
        fvAssert(!cStalled || (cValid && cBits.asUInt === cPrevious),
          s"param_beat_stable__${layer}_${index}_c")
        // 断言：有效 C 通道消息的传输大小不超过 2 字节。
        fvAssert(!cValid || cBits.size <= 1.U,
          s"param_beat_shape__${layer}_${index}_c")
        val dValid = BoringUtils.bore(slice.io.in.d.valid)
        val dReady = BoringUtils.bore(slice.io.in.d.ready)
        val dBits = BoringUtils.bore(slice.io.in.d.bits)
        val dStalled = RegNext(dValid && !dReady, false.B)
        val dPrevious = RegNext(dBits.asUInt)
        // 断言：D 通道阻塞后，下一拍保持 valid 和载荷。
        fvAssert(!dStalled || (dValid && dBits.asUInt === dPrevious),
          s"param_beat_stable__${layer}_${index}_d")
        // 断言：有效 D 通道消息的传输大小不超过 2 字节。
        fvAssert(!dValid || dBits.size <= 1.U,
          s"param_beat_shape__${layer}_${index}_d")
      }
      coupledL2AsL1.zipWithIndex.foreach { case (l1, i) =>
        checkSlice(l1.module.slices.head.asInstanceOf[L1Slice], "l1", i,
          tuning.l1Ways, tuning.l1Sets, tuning.l1Mshrs)
      }
      coupledL2.zipWithIndex.foreach { case (l2, i) =>
        checkSlice(l2.module.slices.head.asInstanceOf[L2Slice], "l2", i,
          tuning.l2Ways, tuning.l2Sets, tuning.l2Mshrs)
      }

      val l3Slice = l3.module.slices.head
      val l3Sync = l3Slice.directorySync.get
      val l3Tags = BoringUtils.bore(l3Sync.io.selfTagArray)
      val l3States = BoringUtils.bore(l3Sync.io.selfDirArray)
      val l3TagWrite = BoringUtils.bore(l3Sync.io.selfTagW)
      val l3InA = l3Slice.sinkA.io.a
      val l3InValid = BoringUtils.bore(l3InA.valid)
      val l3InReady = BoringUtils.bore(l3InA.ready)
      val l3Address = BoringUtils.bore(l3InA.bits.address)
      val l3Set = l3Address(log2Ceil(tuning.l3Sets), 1)
      // 断言：L3 有效 tag 写入的 way 编号必须在配置范围内。
      fvAssert(!l3TagWrite.valid || l3TagWrite.bits.way < tuning.l3Ways.U,
        "param_way_index__l3")
      val l3OldTags = RegNext(l3Tags(l3TagWrite.bits.set))
      val l3Wrote = RegNext(l3TagWrite.valid, false.B)
      val l3WrittenSet = RegNext(l3TagWrite.bits.set)
      val l3WrittenWay = RegNext(l3TagWrite.bits.way)
      // 断言：上一拍写入某个 way 后，同组其他 way 的 tag 保持不变。
      for (way <- 0 until tuning.l3Ways) {
        fvAssert(!l3Wrote || l3WrittenWay === way.U ||
          l3Tags(l3WrittenSet)(way) === l3OldTags(way),
          s"param_way_isolation__l3_$way")
      }
      // 断言：当前观察组内，任意两个有效 way 的 tag 不得重复。
      for (left <- 0 until tuning.l3Ways; right <- left + 1 until tuning.l3Ways) {
        fvAssert(l3States(l3Set)(left).state === 0.U ||
          l3States(l3Set)(right).state === 0.U ||
          l3Tags(l3Set)(left) =/= l3Tags(l3Set)(right),
          s"param_unique_live_tag__l3_${left}_$right")
      }
      // 覆盖：每个 L3 组分别至少接受一次 A 请求。
      for (set <- 0 until tuning.l3Sets) {
        chisel3.ltl.CoverProperty(l3InValid && l3InReady && l3Set === set.U,
          s"param_cover_set__l3_$set")
      }
      // 覆盖：当前观察组的 way 0 和 way 1 同时有效。
      chisel3.ltl.CoverProperty(l3States(l3Set)(0).state =/= 0.U &&
        l3States(l3Set)(1).state =/= 0.U, "param_cover_two_ways__l3")
      val l3Alloc = l3Slice.sinkA.io.alloc
      val l3AllocSet = BoringUtils.bore(l3Alloc.bits.set)
      val l3AllocTag = BoringUtils.bore(l3Alloc.bits.tag)
      val l3AllocOff = BoringUtils.bore(l3Alloc.bits.off)
      // 断言：L3 接受 A 请求时，分配任务的 set/tag/offset 与请求地址一致。
      fvAssert(!l3InValid || !l3InReady ||
        (l3AllocSet === l3Set &&
         l3AllocTag === (l3Address >> (log2Ceil(tuning.l3Sets) + 1)).asUInt &&
         l3AllocOff === l3Address(0)), "param_address_decode__l3")
      val l3Slots = (0 until tuning.l3Mshrs).map { i =>
        (BoringUtils.bore(l3Slice.ms(i).io.status.valid),
         BoringUtils.bore(l3Slice.ms(i).io.status.bits.set),
         BoringUtils.bore(l3Slice.ms(i).io.status.bits.tag))
      }
      val l3Occupied = PopCount(l3Slots.map(_._1))
      // 断言：所观察的普通 MSHR 有效槽位数不超过配置数量。
      fvAssert(l3Occupied <= tuning.l3Mshrs.U, "param_mshr_capacity__l3")
      // 断言：两个有效 MSHR 不得同时跟踪相同的 set/tag。
      for (left <- 0 until tuning.l3Mshrs; right <- left + 1 until tuning.l3Mshrs) {
        val (validA, setA, tagA) = l3Slots(left)
        val (validB, setB, tagB) = l3Slots(right)
        fvAssert(!(validA && validB && setA === setB && tagA === tagB),
          s"param_mshr_unique__l3_${left}_$right")
      }
      val l3Allocations = (0 until tuning.l3Mshrs).map(i =>
        BoringUtils.bore(l3Slice.mshrAlloc.io.alloc(i).valid))
      // 断言：发生 MSHR 分配时尚有空闲容量；不检查事务最终完成。
      fvAssert(!l3Allocations.reduce(_ || _) || l3Occupied < tuning.l3Mshrs.U,
        "param_mshr_progress__l3")
      // 覆盖：至少两个 L3 普通 MSHR 同时有效。
      chisel3.ltl.CoverProperty(l3Occupied >= 2.U, "param_cover_two_mshrs__l3")

      val l3OutA = l3Slice.io.out.a
      val l3OutValid = BoringUtils.bore(l3OutA.valid)
      val l3OutReady = BoringUtils.bore(l3OutA.ready)
      val l3OutBits = BoringUtils.bore(l3OutA.bits)
      val l3BlockRequest = l3OutValid &&
        (l3OutBits.opcode === TLMessages.AcquireBlock ||
         l3OutBits.opcode === TLMessages.AcquirePerm)
      // 断言：有效 AcquireBlock/AcquirePerm 请求按 2 字节块对齐。
      fvAssert(!l3BlockRequest || l3OutBits.address(0) === 0.U,
        "param_block_boundary__l3")
      // 断言：块获取请求 size=1，即传输大小为 2 字节。
      fvAssert(!l3BlockRequest || l3OutBits.size === 1.U,
        "param_block_size__l3")
      // 断言：块获取请求的单字节传输 mask 为 1。
      fvAssert(!l3BlockRequest || l3OutBits.mask === 1.U,
        "param_beat_shape__l3")
      val l3OutStalled = RegNext(l3OutValid && !l3OutReady, false.B)
      val l3OutPrevious = RegNext(l3OutBits.asUInt)
      // 断言：A 通道阻塞后，下一拍保持 valid 和载荷。
      fvAssert(!l3OutStalled ||
        (l3OutValid && l3OutBits.asUInt === l3OutPrevious),
        "param_beat_stable__l3")
      val l3CValid = BoringUtils.bore(l3Slice.io.out.c.valid)
      val l3CReady = BoringUtils.bore(l3Slice.io.out.c.ready)
      val l3CBits = BoringUtils.bore(l3Slice.io.out.c.bits)
      val l3CStalled = RegNext(l3CValid && !l3CReady, false.B)
      val l3CPrevious = RegNext(l3CBits.asUInt)
      // 断言：C 通道阻塞后，下一拍保持 valid 和载荷。
      fvAssert(!l3CStalled || (l3CValid && l3CBits.asUInt === l3CPrevious),
        "param_beat_stable__l3_c")
      // 断言：有效 C 通道消息的传输大小不超过 2 字节。
      fvAssert(!l3CValid || l3CBits.size <= 1.U, "param_beat_shape__l3_c")
      // Observe the cache's D producer, before the environment's TLDelayer.
      val l3DValid = BoringUtils.bore(l3Slice.io.in.d.valid)
      val l3DReady = BoringUtils.bore(l3Slice.io.in.d.ready)
      val l3DBits = BoringUtils.bore(l3Slice.io.in.d.bits)
      val l3DStalled = RegNext(l3DValid && !l3DReady, false.B)
      val l3DPrevious = RegNext(l3DBits.asUInt)
      // 断言：D 通道阻塞后，下一拍保持 valid 和载荷。
      fvAssert(!l3DStalled || (l3DValid && l3DBits.asUInt === l3DPrevious),
        "param_beat_stable__l3_d")
      // 断言：有效 D 通道消息的传输大小不超过 2 字节。
      fvAssert(!l3DValid || l3DBits.size <= 1.U, "param_beat_shape__l3_d")

      val sourceA = l3.module.slices.head.sourceA
      val putValid = BoringUtils.bore(sourceA.io.a.valid)
      val putReady = BoringUtils.bore(sourceA.io.a.ready)
      val putBits = BoringUtils.bore(sourceA.io.a.bits)
      val assembled = BoringUtils.bore(sourceA.s1_pb_latch)
      val isPut = putValid && putBits.opcode === TLMessages.PutFullData
      // 断言：有效 PutFullData 的数据和 mask 与拼接缓存一致。
      fvAssert(!isPut || (putBits.data === assembled.data &&
        putBits.mask === assembled.mask), "param_block_assembly__l3")
      val firstPut = RegInit(false.B)
      val firstAddress = Reg(UInt(putBits.address.getWidth.W))
      val firstSource = Reg(UInt(putBits.source.getWidth.W))
      when(isPut && putReady && putBits.size === 1.U) {
        when(!firstPut) {
          firstPut := true.B
          firstAddress := putBits.address
          firstSource := putBits.source
        }.otherwise {
          firstPut := false.B
        }
      }
      // 断言：记录首拍后，下一次接受的 PutFullData 与首拍地址、source 一致。
      fvAssert(!isPut || !putReady || !firstPut ||
        (putBits.address === firstAddress && putBits.source === firstSource),
        "param_block_assembly__l3_pair")

      // RAM 监测：跟踪首次有效写入选定的一个字节地址，不是全 RAM 并发正确性证明。
      val ramA = ram.module.in.a
      val ramD = ram.module.in.d
      val ramAValid = BoringUtils.bore(ramA.valid)
      val ramAReady = BoringUtils.bore(ramA.ready)
      val ramABits = BoringUtils.bore(ramA.bits)
      val ramDValid = BoringUtils.bore(ramD.valid)
      val ramDReady = BoringUtils.bore(ramD.ready)
      val ramDBits = BoringUtils.bore(ramD.bits)
      val write = ramAValid && ramAReady &&
        (ramABits.opcode === TLMessages.PutFullData ||
         ramABits.opcode === TLMessages.PutPartialData)
      val read = ramAValid && ramAReady && ramABits.opcode === TLMessages.Get
      val known = RegInit(false.B)
      val watchedAddress = Reg(UInt(ramABits.address.getWidth.W))
      val watchedData = Reg(UInt(8.W))
      when(write && !known && ramABits.mask(0)) {
        known := true.B
        watchedAddress := ramABits.address
        watchedData := ramABits.data
      }.elsewhen(write && known && ramABits.address === watchedAddress && ramABits.mask(0)) {
        watchedData := ramABits.data
      }
      val pending = RegInit(false.B)
      val pendingSource = Reg(UInt(ramABits.source.getWidth.W))
      val expectedData = Reg(UInt(8.W))
      val maskedWrite = RegInit(false.B)
      val pendingMaskedWrite = Reg(Bool())
      when(write && known && ramABits.address === watchedAddress) {
        maskedWrite := !ramABits.mask(0)
      }
      when(read && known && ramABits.address === watchedAddress && !pending) {
        pending := true.B
        pendingSource := ramABits.source
        expectedData := watchedData
        pendingMaskedWrite := maskedWrite
      }
      val matchedRead = pending && ramDValid && ramDReady &&
        ramDBits.source === pendingSource && ramDBits.opcode === TLMessages.AccessAckData
      when(matchedRead) { pending := false.B }
      // 断言：source 匹配的读响应握手时，数据等于接受读请求时记录的预期字节。
      fvAssert(!matchedRead || ramDBits.data === expectedData, "param_ram_data__readback")
      // 断言：读请求前最近一次同地址写入 mask=0 时，读回仍等于预期字节。
      fvAssert(!matchedRead || !pendingMaskedWrite || ramDBits.data === expectedData,
        "param_ram_lanes__masked_write")
      // 覆盖：被跟踪地址出现匹配的读响应握手；不单独要求 mask=0 写入发生。
      chisel3.ltl.CoverProperty(matchedRead, "param_cover_ram_readback")
    }
  }
}

object VerifyTop extends App {
  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop()(p)))(config)
  val directory = new File("./Verilog")

  if (!directory.exists()) {
    directory.mkdirs()
  }
  FileRegisters.writeOutputFile(
    "Verilog",
    "VerifyTop.sv",
    ChiselStage.emitSystemVerilog(top.module,
                                  args = Array("--warn-conf", "id=4:s"),
                                  firtoolOpts = Array("--disable-annotation-unknown"))
  )
}

object ParamVerifyTop extends App {
  val entries = args.map(_.split("=", 2))
  require(entries.forall(_.length == 2), "expected key=value arguments")
  require(entries.map(_(0)).distinct.length == entries.length, "duplicate parameter")
  val values = entries.map(x => x(0) -> x(1)).toMap
  val keys = Seq("l1.ways", "l1.sets", "l1.blockBytes", "l1.mshrs", "l1.channelBytes",
    "l2.ways", "l2.sets", "l2.blockBytes", "l2.mshrs", "l2.channelBytes",
    "l3.ways", "l3.sets", "l3.blockBytes", "l3.mshrs", "l3.channelBytes", "ram.width")
  require(values.keySet == (keys :+ "output").toSet, "expected complete candidate and output")
  def get(key: String): Int = values(key).toInt
  for (layer <- Seq("l1", "l2", "l3")) {
    require(get(s"$layer.blockBytes") == 2, s"$layer.blockBytes is fixed at 2")
    require(get(s"$layer.channelBytes") == 1, s"$layer.channelBytes is fixed at 1")
  }
  val tuning = TuningParams(
    get("l1.ways"), get("l1.sets"), get("l1.mshrs"),
    get("l2.ways"), get("l2.sets"), get("l2.mshrs"),
    get("l3.ways"), get("l3.sets"), get("l3.mshrs"), get("ram.width")
  )
  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))))
    case HCCacheParamsKey => HCCacheParameters(echoField = Seq(DirtyField()))
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop(tuning, parameterMode = true)(p)))(config)
  val output = new File(values("output"))
  require(output.getName == "VerifyTop.sv", "output must be VerifyTop.sv")
  output.getParentFile.mkdirs()
  val emitted = ChiselStage.emitSystemVerilog(top.module,
    args = Array("--warn-conf", "id=4:s"),
    firtoolOpts = Array("--disable-annotation-unknown"))
  val resources = emitted.indexOf("// ----- 8< ----- FILE \"./ClockGate.sv\"")
  require(resources > 0 && emitted.substring(resources).contains("firrtl_black_box_resource_files.f"),
    "unexpected firtool resource appendix")
  FileRegisters.writeOutputFile(
    output.getParent,
    output.getName,
    emitted.substring(0, resources)
  )
}
