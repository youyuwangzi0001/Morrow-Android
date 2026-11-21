package studio.morrow.android

object IgnoreBridges {

  data class TodayAccumulator(
    val rounds: Int = 0,
    val tokens: Long = 0L,
    val completed: Int = 0,
    val running: Int = 0,
    val failed: Int = 0,
    val artifacts: Int = 0,
    /** 是否收到过任何一条能证明「今天有活动」的事件 */
    val sawAnything: Boolean = false,
    val epochDay: Long = NO_DAY,
  ) {
    fun toStats(): TodayStatsSnapshot = TodayStatsSnapshot(
      rounds = rounds, tokens = tokens, completed = completed,
      running = running, failed = failed, artifacts = artifacts,
    )
  }

  /** 尚未记录任何一天的哨兵值 */
  const val NO_DAY = -1L
  fun epochDayOf(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
    java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

  fun forToday(acc: TodayAccumulator?, nowMillis: Long): TodayAccumulator {
    val today = epochDayOf(nowMillis)
    if (acc != null && acc.epochDay == today) return acc
    return TodayAccumulator(epochDay = today)
  }

  /** 与 C1 `TodayStats` 同形（避免 state 层依赖 ui 层）。 */
  data class TodayStatsSnapshot(
    val rounds: Int,
    val tokens: Long,
    val completed: Int,
    val running: Int,
    val failed: Int,
    val artifacts: Int,
  )

  fun accumulateUsage(acc: TodayAccumulator, raw: Map<String, Any?>): TodayAccumulator {
    val usage = raw["usage"] as? Map<*, *> ?: return acc   // 整个缺失：不动
    val input = (usage["inputTokens"] as? Number)?.toLong() ?: 0L
    val output = (usage["outputTokens"] as? Number)?.toLong() ?: 0L
    val hasAny = usage.containsKey("inputTokens") || usage.containsKey("outputTokens")
    return acc.copy(
      tokens = acc.tokens + input + output,
      sawAnything = acc.sawAnything || hasAny,
    )
  }

  /** `run_started` → rounds + 1，running + 1。 */
  fun onRunStarted(acc: TodayAccumulator): TodayAccumulator = acc.copy(
    rounds = acc.rounds + 1,
    running = acc.running + 1,
    sawAnything = true,
  )

  fun onRunFinished(acc: TodayAccumulator, reason: String?): TodayAccumulator {
    val isFailed = reason == "error" || reason == "max-steps" || reason == "cancelled"
    return acc.copy(
      running = maxOf(0, acc.running - 1),
      completed = acc.completed + if (isFailed) 0 else 1,
      failed = acc.failed + if (isFailed) 1 else 0,
      sawAnything = true,
    )
  }

  /** `artifacts_updated` → 产物数 = 数组长度（整表，不是累加）。 */
  fun onArtifactsCount(acc: TodayAccumulator, count: Int): TodayAccumulator =
    acc.copy(artifacts = count, sawAnything = acc.sawAnything || count > 0)

  /** 与 C1 `Artifact` 同形。`bytes` 为 null = 拿不到（path 型产物）。 */
  data class ArtifactView(
    val name: String,
    val format: String,
    val bytes: Long?,
    val atMillis: Long,
  )

  /** 桌面 `ArtifactKind` → 显示用格式标签（6 种，逐一对应）。 */
  private val KIND_TO_FORMAT = mapOf(
    "html" to "HTML",
    "markdown" to "MD",
    "code" to "CODE",
    "svg" to "SVG",
    "text" to "TXT",
    "file" to "FILE",
  )

  fun artifactsOf(raw: Map<String, Any?>): List<ArtifactView>? {
    val arr = raw["artifacts"] as? List<*> ?: return null
    return arr.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val title = (m["title"] as? String)?.takeIf { it.isNotBlank() }
      val path = m["path"] as? String
      val kind = m["kind"] as? String ?: "file"
      val name = title
        ?: path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        ?: "未命名产物"
      val at = (m["updatedAt"] as? Number)?.toLong()
        ?: (m["createdAt"] as? Number)?.toLong()
        ?: 0L
      val content = m["content"] as? String
      val bytes = content?.toByteArray(Charsets.UTF_8)?.size?.toLong()
      ArtifactView(
        name = name,
        format = KIND_TO_FORMAT[kind] ?: kind.uppercase(),
        bytes = bytes,
        atMillis = at,
      )
    }
  }

  /** 与 C3 `MemberProgress` 同形（去掉 UI 层才需要的 operations / usage）。 */
  data class MemberView(
    val index: Int,
    val title: String,
    val agent: String,
    val agentTitle: String?,
    val state: String,        // 桌面原值 queued/running/... ，UI 层再转枚举
    val stepCount: Int?,
    val note: String?,
    val summary: String?,
    val error: String?,
    /** 桌面任务 id；手机干预（停止/重试/发指令）需要它。null = 旧版桌面未下发。 */
    val taskId: String? = null,
    val stuck: MemberStuckView? = null,
  )

  /** 卡住告警的手机侧形状（桌面 `phoneStuck()` 的对端） */
  data class MemberStuckView(
    val reason: String,
    val stepsSinceProgress: Int?,
    val lastOperation: String?,
  )

  /** `works.get` 结果 → 作品摘要列表。缺 `works` 数组 = 未知（null），不是空列表。 */
  fun worksOf(raw: Map<String, Any?>): List<WorkView>? {
    val arr = raw["works"] as? List<*> ?: return null
    return arr.mapNotNull { item ->
      val m = item as? Map<*, *> ?: return@mapNotNull null
      val id = m["id"] as? String ?: return@mapNotNull null
      WorkView(
        id = id,
        title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: "未命名作品",
        kind = (m["kind"] as? String) ?: "short",
        logline = (m["logline"] as? String).orEmpty(),
        updatedAtMillis = (m["updatedAtMillis"] as? Number)?.toLong() ?: 0L,
        stage = (m["stage"] as? String) ?: "blueprint",
        progress = ((m["progress"] as? Number)?.toInt() ?: 0).coerceIn(0, 100),
        chapters = (m["chapters"] as? Number)?.toInt() ?: 0,
        shots = (m["shots"] as? Number)?.toInt() ?: 0,
        assets = (m["assets"] as? Number)?.toInt() ?: 0,
        words = (m["words"] as? Number)?.toLong() ?: 0L,
        blockingChecks = (m["blockingChecks"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        stageStatuses = (m["stageStatuses"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
          val stageId = k as? String ?: return@mapNotNull null
          val sm = v as? Map<*, *> ?: return@mapNotNull null
          val status = (sm["status"] as? String)?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
          stageId to StageStatusView(
            status = status,
            progress = (sm["progress"] as? Number)?.toInt()?.coerceIn(0, 100),
            message = (sm["message"] as? String)?.takeIf { it.isNotBlank() },
          )
        }.toMap(),
        products = (m["products"] as? List<*>).orEmpty().mapNotNull { item ->
          val pm = item as? Map<*, *> ?: return@mapNotNull null
          val productId = pm["id"] as? String ?: return@mapNotNull null
          WorkProductView(
            id = productId,
            label = (pm["label"] as? String)?.takeIf { it.isNotBlank() } ?: "未命名产物",
            kind = (pm["kind"] as? String) ?: "other",
            bytes = (pm["bytes"] as? Number)?.toLong() ?: 0L,
            createdAtMillis = (pm["createdAtMillis"] as? Number)?.toLong() ?: 0L,
          )
        },
      )
    }
  }

  /** `super_updated` → 协作总目标（手机不展示完整计划，只展示这一句）。 */
  fun goalOf(raw: Map<String, Any?>): String? {
    val state = raw["state"] as? Map<*, *> ?: return null
    return (state["goal"] as? String)?.takeIf { it.isNotBlank() }
  }

  /** `super_updated` → 协作项目/会话 id（干预时定位成员用）。 */
  fun projectIdOf(raw: Map<String, Any?>): String? =
    ((raw["state"] as? Map<*, *>)?.get("projectId") as? String)?.takeIf { it.isNotBlank() }

  fun sessionIdOf(raw: Map<String, Any?>): String? =
    (raw["sessionId"] as? String)?.takeIf { it.isNotBlank() }

  /** `super_updated` → 成员列表。`state` 为 null 或 `tasks` 空 → 返回空列表（协作结束）。 */
  fun membersOf(raw: Map<String, Any?>): List<MemberView> {
    val state = raw["state"] as? Map<*, *> ?: return emptyList()
    val tasks = state["tasks"] as? List<*> ?: return emptyList()
    return tasks.mapNotNull { t ->
      val m = t as? Map<*, *> ?: return@mapNotNull null
      val idx = (m["index"] as? Number)?.toInt() ?: return@mapNotNull null
      MemberView(
        index = idx,
        title = (m["title"] as? String)?.takeIf { it.isNotBlank() } ?: "成员 $idx",
        agent = (m["agent"] as? String) ?: "general",
        agentTitle = m["agentTitle"] as? String,
        state = (m["status"] as? String) ?: "queued",
        stepCount = (m["stepCount"] as? Number)?.toInt(),
        note = m["note"] as? String,
        summary = m["summary"] as? String,
        error = m["error"] as? String,
        taskId = (m["taskId"] as? String)?.takeIf { it.isNotBlank() },
        stuck = stuckOf(m),
      )
    }
  }

  /** 成员上的卡住告警。缺 reason 视为「没卡住」——没有依据的告警不该显示。 */
  private fun stuckOf(m: Map<*, *>): MemberStuckView? {
    val s = m["stuck"] as? Map<*, *> ?: return null
    val reason = (s["reason"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return MemberStuckView(
      reason = reason,
      stepsSinceProgress = (s["stepsSinceProgress"] as? Number)?.toInt(),
      lastOperation = (s["lastOperation"] as? String)?.takeIf { it.isNotBlank() },
    )
  }

  fun mergeMember(existing: List<MemberView>, raw: Map<String, Any?>): List<MemberView> {
    val mt = raw["memberTask"] as? Map<*, *> ?: return existing
    val idx = (mt["index"] as? Number)?.toInt() ?: return existing

    val prev = existing.firstOrNull { it.index == idx }
    val merged = MemberView(
      index = idx,
      title = if (mt.containsKey("title")) (mt["title"] as? String) ?: "" else prev?.title ?: "成员 $idx",
      agent = if (mt.containsKey("agent")) (mt["agent"] as? String) ?: "general" else prev?.agent ?: "general",
      agentTitle = if (mt.containsKey("agentTitle")) mt["agentTitle"] as? String else prev?.agentTitle,
      state = if (mt.containsKey("status")) (mt["status"] as? String) ?: "queued" else prev?.state ?: "queued",
      stepCount = if (mt.containsKey("stepCount")) (mt["stepCount"] as? Number)?.toInt() else prev?.stepCount,
      note = if (mt.containsKey("note")) mt["note"] as? String else prev?.note,
      summary = if (mt.containsKey("summary")) mt["summary"] as? String else prev?.summary,
      error = if (mt.containsKey("error")) mt["error"] as? String else prev?.error,
    )
    return if (prev == null) existing + merged
    else existing.map { if (it.index == idx) merged else it }
  }
}
