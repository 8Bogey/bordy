package com.bordy.app.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.bordy.app.R
import com.bordy.app.ui.animations.bouncyClickable
import com.bordy.app.utils.BrandYellow
import com.bordy.app.utils.CalculatorEngine
import com.bordy.app.utils.CalculatorHistoryManager
import com.bordy.app.utils.DangerRed
import com.bordy.app.utils.OnBrandYellow
import com.bordy.app.utils.SellGreen
import com.bordy.app.utils.SellGreenDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val historyManager = remember { CalculatorHistoryManager(context) }

    // rememberSaveable حتى تبقى المعادلة عند تدوير الشاشة أفقياً
    var expression by rememberSaveable { mutableStateOf("") }
    var cursorPosition by rememberSaveable { mutableStateOf(0) }
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    var showHistory by remember { mutableStateOf(false) }
    var historyList by remember { mutableStateOf(historyManager.getHistory()) }
    var isResultCalculated by rememberSaveable { mutableStateOf(false) }

    val liveResult by remember(expression) {
        derivedStateOf {
            if (expression.isEmpty()) return@derivedStateOf ""
            var exprToEval = expression
            while (exprToEval.isNotEmpty() && exprToEval.last() in "+-×÷") {
                exprToEval = exprToEval.dropLast(1)
            }
            if (exprToEval.isEmpty()) return@derivedStateOf ""

            val openParen = exprToEval.count { it == '(' }
            val closeParen = exprToEval.count { it == ')' }
            if (openParen > closeParen) {
                exprToEval += ")".repeat(openParen - closeParen)
            }

            if (!exprToEval.contains(Regex("[+\\-×÷%()]"))) "" else CalculatorEngine.evaluate(exprToEval)
        }
    }

    val displayScrollState = rememberScrollState()

    // تمرير تلقائي لمتابعة الأرقام عند كل كتابة
    LaunchedEffect(expression, cursorPosition) {
        displayScrollState.animateScrollTo(displayScrollState.maxValue)
    }

    // حركة وميض المؤشر المرئي الواضح (500 مللي ثانية مرئي / 500 مللي ثانية مخفي)
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 999
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorAlpha"
    )

    // تصغير تدريجي ودقيق للأرقام لمنع النزول خارج الشاشة
    val exprFontSize = when {
        expression.length > 35 -> 15.sp
        expression.length > 22 -> 18.sp
        expression.length > 12 -> 22.sp
        else -> 26.sp
    }
    val exprLineHeight = when {
        expression.length > 35 -> 20.sp
        expression.length > 22 -> 24.sp
        expression.length > 12 -> 28.sp
        else -> 32.sp
    }

    val resultFontSize = when {
        liveResult.length > 18 -> 17.sp
        liveResult.length > 10 -> 21.sp
        else -> 24.sp
    }

    // إدراج النص عند موضع المؤشر بالضبط
    fun insertText(str: String) {
        if (isResultCalculated) {
            if (str in listOf("+", "-", "×", "÷", "%")) {
                isResultCalculated = false
            } else {
                expression = str
                cursorPosition = str.length
                isResultCalculated = false
                return
            }
        }
        val currentText = expression
        val pos = cursorPosition.coerceIn(0, currentText.length)
        expression = currentText.substring(0, pos) + str + currentText.substring(pos)
        cursorPosition = pos + str.length
    }

    // حذف خانة واحدة عند موضع المؤشر (زر حذف وحيد في الشاشة)
    fun deleteAtCursor() {
        isResultCalculated = false
        val currentText = expression
        if (currentText.isEmpty()) return
        val pos = cursorPosition.coerceIn(0, currentText.length)
        if (pos > 0) {
            expression = currentText.substring(0, pos - 1) + currentText.substring(pos)
            cursorPosition = pos - 1
        }
    }

    fun pressEqual() {
        // نأخذ النتيجة قبل تغيير المعادلة، لأن liveResult يُعاد حسابه فور تغيّرها
        val result = liveResult
        if (result.isNotEmpty()) {
            historyManager.save(expression, result)
            expression = result
            cursorPosition = result.length
            isResultCalculated = true
        }
    }

    fun handleKey(label: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (label) {
            "C" -> {
                expression = ""
                cursorPosition = 0
                isResultCalculated = false
            }
            "⌫" -> {
                deleteAtCursor()
            }
            "()" -> {
                val openCount = expression.count { it == '(' }
                val closeCount = expression.count { it == ')' }
                val pos = cursorPosition.coerceIn(0, expression.length)
                val charBefore = if (pos > 0) expression[pos - 1] else null
                if (openCount > closeCount && (charBefore?.isDigit() == true || charBefore == ')')) {
                    insertText(")")
                } else {
                    if (charBefore?.isDigit() == true || charBefore == ')') {
                        insertText("×(")
                    } else {
                        insertText("(")
                    }
                }
            }
            "%" -> {
                val pos = cursorPosition.coerceIn(0, expression.length)
                val charBefore = if (pos > 0) expression[pos - 1] else null
                if (charBefore != null && (charBefore.isDigit() || charBefore == ')')) {
                    insertText("%")
                }
            }
            "." -> {
                val pos = cursorPosition.coerceIn(0, expression.length)
                val beforeCursor = expression.substring(0, pos)
                val lastSegment = beforeCursor.split(Regex("[+\\-×÷%()]")).lastOrNull() ?: ""
                if (!lastSegment.contains(".")) {
                    insertText(if (lastSegment.isEmpty()) "0." else ".")
                }
            }
            in listOf("+", "-", "×", "÷") -> {
                if (isResultCalculated) {
                    isResultCalculated = false
                    insertText(label)
                } else {
                    val pos = cursorPosition.coerceIn(0, expression.length)
                    if (pos > 0 && expression[pos - 1] in listOf('+', '-', '×', '÷')) {
                        expression = expression.substring(0, pos - 1) + label + expression.substring(pos)
                    } else {
                        insertText(label)
                    }
                }
            }
            else -> {
                // الأرقام 0-9
                insertText(label)
            }
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "الحاسبة",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(BrandYellow)
                        .bouncyClickable(scaleDownTo = 0.88f) {
                            historyList = historyManager.getHistory()
                            showHistory = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_nav_history),
                        contentDescription = "السجل",
                        tint = SellGreenDark,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val isLandscape = maxWidth > maxHeight
            val keySpacing = 6.dp

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                if (isLandscape) {
                    // في العرض الأفقي: الشاشة يميناً والمفاتيح عمودياً بجانبها حتى لا تُقص
                    val keySize = minOf(
                        (maxWidth / 2 - 44.dp - keySpacing * 3) / 4,
                        (maxHeight - 54.dp) / 5
                    ).coerceAtLeast(34.dp)

                    // خطوط أكبر في العرض الأفقي لملء المساحة المتاحة
                    val wideExprFontSize = when {
                        expression.length > 40 -> 18.sp
                        expression.length > 26 -> 22.sp
                        expression.length > 14 -> 26.sp
                        else -> 34.sp
                    }
                    val wideResultFontSize = if (liveResult.length > 14) 22.sp else 28.sp

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CalculatorDisplay(
                            expression = expression,
                            cursorPosition = cursorPosition,
                            textLayoutResult = textLayoutResult,
                            onTextLayout = { textLayoutResult = it },
                            liveResult = liveResult,
                            cursorAlpha = cursorAlpha,
                            exprFontSize = wideExprFontSize,
                            exprLineHeight = (wideExprFontSize.value + 8).sp,
                            resultFontSize = wideResultFontSize,
                            scrollState = displayScrollState,
                            onCursorMove = { cursorPosition = it },
                            centerVertically = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        Spacer(Modifier.width(12.dp))
                        CalculatorKeypad(
                            keySize = keySize,
                            keySpacing = keySpacing,
                            onKey = { handleKey(it) },
                            onEqual = { pressEqual() }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        CalculatorDisplay(
                            expression = expression,
                            cursorPosition = cursorPosition,
                            textLayoutResult = textLayoutResult,
                            onTextLayout = { textLayoutResult = it },
                            liveResult = liveResult,
                            cursorAlpha = cursorAlpha,
                            exprFontSize = exprFontSize,
                            exprLineHeight = exprLineHeight,
                            resultFontSize = resultFontSize,
                            scrollState = displayScrollState,
                            onCursorMove = { cursorPosition = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )

                        Spacer(Modifier.height(8.dp))

                        CalculatorKeypad(
                            keySize = null,
                            keySpacing = keySpacing,
                            onKey = { handleKey(it) },
                            onEqual = { pressEqual() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.padding(16.dp).fillMaxHeight(0.7f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("سجل العمليات الحسابية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { historyManager.clear(); historyList = emptyList() }) {
                            Text("مسح السجل", color = DangerRed)
                        }
                    }
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    if (historyList.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("ما عندك حسابات سابقة", color = MaterialTheme.colorScheme.onSurface.copy(alpha=0.6f))
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(historyList) { item ->
                                Card(
                                    onClick = {
                                        expression = item.expression
                                        cursorPosition = item.expression.length
                                        isResultCalculated = false
                                        showHistory = false
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = item.expression,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Left,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = item.result,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = SellGreen,
                                            textAlign = TextAlign.Left,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// منطقة عرض المعادلة والنتيجة مع المؤشر الوامض
@Composable
private fun CalculatorDisplay(
    expression: String,
    cursorPosition: Int,
    textLayoutResult: TextLayoutResult?,
    onTextLayout: (TextLayoutResult) -> Unit,
    liveResult: String,
    cursorAlpha: Float,
    exprFontSize: TextUnit,
    exprLineHeight: TextUnit,
    resultFontSize: TextUnit,
    scrollState: ScrollState,
    onCursorMove: (Int) -> Unit,
    centerVertically: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = if (centerVertically) Alignment.CenterStart else Alignment.BottomStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = if (centerVertically) Arrangement.Center else Arrangement.Bottom
        ) {
            // منطقة المعادلة مع دعم تحديد موضع المؤشر باللمس ومؤشر مرئي وامض
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(expression) {
                        detectTapGestures { tapOffset ->
                            val layout = textLayoutResult
                            if (layout != null && expression.isNotEmpty()) {
                                val index = layout.getOffsetForPosition(tapOffset)
                                onCursorMove(index.coerceIn(0, expression.length))
                            } else {
                                onCursorMove(expression.length)
                            }
                        }
                    }
            ) {
                if (expression.isEmpty()) {
                    Text(
                        text = "0",
                        fontSize = exprFontSize,
                        lineHeight = exprLineHeight,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        textAlign = TextAlign.Left
                    )
                }

                Text(
                    text = if (expression.isEmpty()) " " else expression,
                    onTextLayout = onTextLayout,
                    style = TextStyle(
                        fontSize = exprFontSize,
                        lineHeight = exprLineHeight,
                        fontWeight = FontWeight.Normal,
                        color = if (expression.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Left
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            if (cursorAlpha > 0.5f) {
                                val layout = textLayoutResult
                                val rect = if (layout != null) {
                                    // قد يتأخر تحديث التخطيط عن النص لحظياً، لذا نطابق الحد مع نص التخطيط نفسه
                                    val laidOutLength = layout.layoutInput.text.length
                                    layout.getCursorRect(cursorPosition.coerceIn(0, laidOutLength))
                                } else null

                                if (rect != null) {
                                    val strokeW = 2.5.dp.toPx()
                                    val x = rect.left.coerceAtLeast(strokeW / 2)
                                    val top = rect.top + 2.dp.toPx()
                                    val bottom = rect.bottom - 2.dp.toPx()
                                    drawLine(
                                        color = SellGreen,
                                        start = Offset(x, top),
                                        end = Offset(x, bottom),
                                        strokeWidth = strokeW,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                )
            }

            if (liveResult.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "= $liveResult",
                    fontSize = resultFontSize,
                    lineHeight = (resultFontSize.value + 6).sp,
                    fontWeight = FontWeight.Bold,
                    color = SellGreen,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// لوحة المفاتيح الحسابية (زر حذف وحيد ⌫)
@Composable
private fun CalculatorKeypad(
    keySize: Dp?,
    keySpacing: Dp,
    onKey: (String) -> Unit,
    onEqual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttons = listOf(
        listOf("C", "⌫", "%", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "-"),
        listOf("1", "2", "3", "+"),
        listOf("()", "0", ".", "=")
    )

    Column(modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        buttons.forEach { row ->
            Row(
                modifier = (if (keySize != null) Modifier.width(keySize * 4 + keySpacing * 3) else Modifier.fillMaxWidth())
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(keySpacing)
            ) {
                row.forEach { label ->
                    val isOperator = label in listOf("÷", "×", "-", "+", "=")
                    val isAction = label in listOf("C", "()", "%", "⌫")
                    val keyModifier = if (keySize != null) Modifier.size(keySize) else Modifier.weight(1f).aspectRatio(1f)

                    if (label == "=") {
                        Box(
                            modifier = keyModifier
                                .shadow(8.dp, CircleShape)
                                .clip(CircleShape)
                                .background(SellGreen)
                                .bouncyClickable(
                                    scaleDownTo = 0.9f,
                                    hapticType = HapticFeedbackType.TextHandleMove
                                ) { onEqual() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "=",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = { onKey(label) },
                            modifier = keyModifier.clip(CircleShape),
                            contentPadding = PaddingValues(0.dp),
                            border = if (isOperator || isAction) null else BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    label == "⌫" -> DangerRed
                                    isOperator -> BrandYellow
                                    isAction -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                contentColor = when {
                                    label == "⌫" -> Color.White
                                    isOperator -> OnBrandYellow
                                    isAction -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        ) {
                            if (label == "⌫") {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "حذف", modifier = Modifier.size(22.dp))
                            } else {
                                Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
