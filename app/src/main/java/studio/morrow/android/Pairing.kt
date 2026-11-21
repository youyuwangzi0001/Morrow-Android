package studio.morrow.android

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val TAG_PAIRING_SCREEN = "pairing-screen"
const val TAG_PAIRING_PASTE_FIELD = "pairing-paste-field"
const val TAG_PAIRING_SUBMIT_TICKET = "pairing-submit-ticket"
const val TAG_PAIRING_CODE_FIELD = "pairing-code-field"
const val TAG_PAIRING_SUBMIT_CODE = "pairing-submit-code"
const val TAG_PAIRING_RETRY = "pairing-retry"
const val TAG_PAIRING_CANCEL = "pairing-cancel"
const val TAG_PAIRING_COUNTDOWN = "pairing-countdown"
const val TAG_PAIRING_HINT = "pairing-hint"
const val TAG_PAIRING_COPY_REQUEST = "pairing-copy-request"

@Composable
fun PairingScreen(
  state: PairingFlow.State,
  nowMillis: Long,
  onPasteTicket: (String) -> Unit,
  onSubmitCode: (String) -> Unit,
  onContinueToCode: () -> Unit,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
  pairRequest: String? = null,
  /** 复制配对请求（由 Activity 用系统剪贴板实现，便于测试注入） */
  onCopyPairRequest: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Box(
    modifier
      .fillMaxSize()
      .background(MorrowTheme.bg)
      .testTag(TAG_PAIRING_SCREEN)
  ) {
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 28.dp)
        .widthIn(max = 560.dp),
    ) {
      Text(
        text = "MORROW · 配对",
        color = MorrowTheme.hint,
        fontSize = MorrowType.xs,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.4.sp,
      )
      Spacer(Modifier.height(20.dp))

      when (state) {
        is PairingFlow.State.Idle,
        is PairingFlow.State.Scanning,
        -> PasteTicketBlock(
          pairRequest = pairRequest,
          onCopyPairRequest = onCopyPairRequest,
          onPasteTicket = onPasteTicket,
        )

        is PairingFlow.State.TicketReady ->
          TicketReadyBlock(state, nowMillis, onContinueToCode)

        is PairingFlow.State.CodeEntering ->
          CodeEntryBlock(state, nowMillis, onSubmitCode)

        is PairingFlow.State.Verifying -> WaitingBlock("正在等电脑确认…")
        is PairingFlow.State.Saving -> WaitingBlock("正在保存配对信息…")
        is PairingFlow.State.Paired -> PairedBlock()
        is PairingFlow.State.Failed -> FailedBlock(state, onRetry, onCancel)
      }

      Spacer(Modifier.height(28.dp))
      if (state !is PairingFlow.State.Paired) {
        Text(
          text = "取消",
          color = MorrowTheme.hint,
          fontSize = MorrowType.base,
          modifier = Modifier
            .testTag(TAG_PAIRING_CANCEL)
            .clickable(onClick = onCancel)
            .padding(vertical = 10.dp),
        )
      }
    }
  }
}

@Composable
private fun PasteTicketBlock(
  pairRequest: String?,
  onCopyPairRequest: (String) -> Unit,
  onPasteTicket: (String) -> Unit,
) {
  var text by remember { mutableStateOf("") }
  val p = MorrowTheme.palette

  if (pairRequest != null) {
    Text(
      text = "第一步 · 把你的配对请求交给电脑",
      color = p.text,
      fontSize = MorrowType.h2,
      fontWeight = FontWeight.Medium,
      lineHeight = 24.sp,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      text = "复制下面这串，粘到电脑「设置 → 手机配对」的第一个输入框里。电脑需要用它生成配对码。",
      color = MorrowTheme.hint,
      fontSize = MorrowType.base,
      lineHeight = 20.sp,
    )
    Spacer(Modifier.height(10.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .border(1.dp, p.line, RoundedCornerShape(9.dp))
        .padding(12.dp)
    ) {
      Text(
        text = pairRequest,
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace,
        maxLines = 3,
      )
    }
    Spacer(Modifier.height(10.dp))
    PrimaryButton(
      text = "复制配对请求",
      onClick = { onCopyPairRequest(pairRequest) },
      modifier = Modifier
        .fillMaxWidth()
        .testTag(TAG_PAIRING_COPY_REQUEST),
    )
    Spacer(Modifier.height(26.dp))
    Text(
      text = "第二步 · 粘贴电脑给的配对码",
      color = p.text,
      fontSize = MorrowType.h2,
      fontWeight = FontWeight.Medium,
      lineHeight = 24.sp,
    )
    Spacer(Modifier.height(8.dp))
  } else {
    Text(
      text = "把电脑上的配对码粘贴到这里",
      color = p.text,
      fontSize = MorrowType.h2,
      fontWeight = FontWeight.Medium,
      lineHeight = 24.sp,
    )
  }

  Text(
    text = "在电脑上打开「设置 → 手机配对」，复制那一串以 ey 开头的文本。",
    color = MorrowTheme.hint,
    fontSize = MorrowType.base,
    lineHeight = 20.sp,
  )
  Spacer(Modifier.height(16.dp))

  Box(
    Modifier
      .fillMaxWidth()
      .border(1.dp, if (text.isEmpty()) p.line else p.patina, RoundedCornerShape(9.dp))
      .padding(14.dp)
  ) {
    if (text.isEmpty()) {
      Text(
        text = "eyJkZXZpY2VJZCI6...",
        color = MorrowTheme.hint,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace,
      )
    }
    BasicTextField(
      value = text,
      onValueChange = { text = it.trim() },
      textStyle = TextStyle(
        color = p.text,
        fontSize = MorrowType.sm,
        fontFamily = FontFamily.Monospace,
      ),
      cursorBrush = SolidColor(p.gold),
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
        .testTag(TAG_PAIRING_PASTE_FIELD),
    )
  }

  Spacer(Modifier.height(16.dp))
  PrimaryButton(
    text = "下一步",
    onClick = { onPasteTicket(text) },
    enabled = text.isNotBlank(),
    modifier = Modifier
      .fillMaxWidth()
      .testTag(TAG_PAIRING_SUBMIT_TICKET),
  )
}

@Composable
private fun TicketReadyBlock(
  state: PairingFlow.State.TicketReady,
  nowMillis: Long,
  onContinue: () -> Unit,
) {
  val p = MorrowTheme.palette
  Text(
    text = "配对码已读取",
    color = p.text,
    fontSize = MorrowType.h2,
    fontWeight = FontWeight.Medium,
  )
  Spacer(Modifier.height(10.dp))
  Text(
    text = "电脑：${state.ticket.deviceId}",
    color = p.text,
    fontSize = MorrowType.body,
  )
  val remain = ((state.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  Spacer(Modifier.height(6.dp))
  Text(
    text = "剩余 $remain 秒",
    color = if (remain <= 15) p.rust else p.amber,
    fontSize = MorrowType.sm,
    fontFamily = FontFamily.Monospace,
    modifier = Modifier.testTag(TAG_PAIRING_COUNTDOWN),
  )
  Spacer(Modifier.height(20.dp))
  PrimaryButton(text = "输入六位码", onClick = onContinue, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CodeEntryBlock(
  state: PairingFlow.State.CodeEntering,
  nowMillis: Long,
  onSubmitCode: (String) -> Unit,
) {
  var code by remember { mutableStateOf("") }
  val p = MorrowTheme.palette
  val remain = ((state.expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

  Text(
    text = "输入电脑上显示的六位码",
    color = p.text,
    fontSize = MorrowType.h2,
    fontWeight = FontWeight.Medium,
    lineHeight = 24.sp,
  )
  Spacer(Modifier.height(10.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "剩余 $remain 秒",
      color = if (remain <= 15) p.rust else p.amber,
      fontSize = MorrowType.sm,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.testTag(TAG_PAIRING_COUNTDOWN),
    )
    Spacer(Modifier.width(14.dp))
    Text(
      text = "还可以试 ${state.attemptsLeft} 次",
      color = if (state.attemptsLeft <= 1) p.rust else MorrowTheme.hint,
      fontSize = MorrowType.sm,
    )
  }

  Spacer(Modifier.height(16.dp))
  Box(
    Modifier
      .fillMaxWidth()
      .border(
        if (state.lastMismatch) 1.5.dp else 1.dp,
        if (state.lastMismatch) p.rust else p.line,
        RoundedCornerShape(9.dp),
      )
      .padding(vertical = 16.dp, horizontal = 18.dp)
  ) {
    if (code.isEmpty()) {
      Text(
        text = "000000",
        color = MorrowTheme.hint,
        fontSize = MorrowType.displaySm,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    BasicTextField(
      value = code,
      onValueChange = { input ->
        code = input.filter { it.isDigit() }.take(6)
      },
      textStyle = TextStyle(
        color = p.text,
        fontSize = MorrowType.displaySm,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
      ),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      cursorBrush = SolidColor(p.gold),
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .testTag(TAG_PAIRING_CODE_FIELD),
    )
  }

  if (state.lastMismatch) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = "六位码不对，再试一次",
      color = p.rust,
      fontSize = MorrowType.sm,
      modifier = Modifier.testTag(TAG_PAIRING_HINT),
    )
  }

  Spacer(Modifier.height(18.dp))
  PrimaryButton(
    text = "确认",
    onClick = { onSubmitCode(code) },
    enabled = code.length == 6,
    modifier = Modifier
      .fillMaxWidth()
      .testTag(TAG_PAIRING_SUBMIT_CODE),
  )
}

@Composable
private fun WaitingBlock(text: String) {
  val p = MorrowTheme.palette
  // 又不抢六位码的注意力。
  val alpha by animateFloatAsState(
    targetValue = 0.35f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "pairing-pulse",
  )
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
        Modifier
          .width(10.dp)
          .height(10.dp)
          .background(p.patina.copy(alpha = alpha), RoundedCornerShape(2.dp))
      )
      Spacer(Modifier.height(14.dp))
      Text(text = text, color = MorrowTheme.hint, fontSize = MorrowType.base)
    }
  }
}

@Composable
private fun PairedBlock() {
  Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "配对成功",
        color = MorrowTheme.palette.patina,
        fontSize = MorrowType.title,
        fontWeight = FontWeight.Medium,
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = "正在回到工位…",
        color = MorrowTheme.hint,
        fontSize = MorrowType.base,
      )
    }
  }
}

@Composable
private fun FailedBlock(
  state: PairingFlow.State.Failed,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
) {
  val p = MorrowTheme.palette
  Text(
    text = "配对没有完成",
    color = p.rust,
    fontSize = MorrowType.h2,
    fontWeight = FontWeight.Medium,
  )
  Spacer(Modifier.height(10.dp))
  Text(
    text = state.failure.userHint,
    color = p.text,
    fontSize = MorrowType.body,
    lineHeight = 23.sp,
    modifier = Modifier.testTag(TAG_PAIRING_HINT),
  )
  if (state.detail.isNotBlank()) {
    Spacer(Modifier.height(8.dp))
    Text(
      text = state.detail,
      color = MorrowTheme.hint,
      fontSize = MorrowType.xs,
      fontFamily = FontFamily.Monospace,
    )
  }
  Spacer(Modifier.height(20.dp))
  // 「你确定要取消吗」—— 多一次无意义的点击。
  if (state.failure.canRetryFromScan) {
    PrimaryButton(
      text = "重新配对",
      onClick = onRetry,
      modifier = Modifier
        .fillMaxWidth()
        .testTag(TAG_PAIRING_RETRY),
    )
    Spacer(Modifier.height(10.dp))
    Text(
      text = "回到工位",
      color = MorrowTheme.hint,
      fontSize = MorrowType.base,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onCancel)
        .padding(vertical = 8.dp),
    )
  }
}

@Composable
private fun PrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val p = MorrowTheme.palette
  Box(
    modifier
      .background(
        if (enabled) p.patina.copy(alpha = 0.14f) else Color.Transparent,
        RoundedCornerShape(5.dp),
      )
      .border(
        1.dp,
        if (enabled) p.patina.copy(alpha = 0.55f) else p.line,
        RoundedCornerShape(5.dp),
      )
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(vertical = 13.dp),
    Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (enabled) p.patina else MorrowTheme.hint,
      fontSize = MorrowType.body,
      fontWeight = FontWeight.Medium,
    )
  }
}
