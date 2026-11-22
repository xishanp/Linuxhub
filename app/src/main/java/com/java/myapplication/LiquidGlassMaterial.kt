package com.java.myapplication

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.sqrt

internal object LotusPalette {
    val Canvas = Color(0xFFF6F6F9)
    val Ink = Color(0xFF1C1C1E)
}

internal enum class LotusSpringPreset(val stiffness: Float, val damping: Float) {
    Smooth(170f, 26f),
    Snappy(420f, 30f),
    Bouncy(300f, 14f),
}

internal fun <T> lotusSpring(preset: LotusSpringPreset = LotusSpringPreset.Snappy): SpringSpec<T> {
    val dampingRatio = preset.damping / (2f * sqrt(preset.stiffness))
    return spring(dampingRatio = dampingRatio, stiffness = preset.stiffness)
}

internal fun <T> lotusPressSpring(): SpringSpec<T> = lotusSpring(LotusSpringPreset.Snappy)
internal fun <T> lotusReleaseSpring(): SpringSpec<T> = lotusSpring(LotusSpringPreset.Smooth)



/**
 * Lotus AI 的液态玻璃基础设施。
 *
 * ## 两层 backdrop（这是"背景模糊"能看见的关键）
 *
 * ```
 * ① Box.layerBackdrop(baseBackdrop) { Box(纯色底) }        ← 底色在内层，才会被录进纹理
 * ② Box.layerBackdrop(pageBackdrop) { 页面内容() }        ← 立绘 / 文案 / 引导语录在这里
 *     内部 LocalGlassBackdrop = baseBackdrop（页面里的玻璃采样纯色底，不会自采样递归）
 * ③ CompositionLocalProvider(LocalGlassBackdrop = combined(base, page)) { 悬浮玻璃层 }
 * ```
 *
 * **为什么必须两层**：`blur()` 模糊的是"玻璃后面"的内容。如果只录纯白底色，
 * 输入条被键盘顶上内容层时，它背后是白的，`blur(白)=白` —— 看起来就是"没接模糊"。
 * 把页面内容录进 pageBackdrop 之后，输入条压到立绘/引导语上就会真的把它们糊掉。
 *
 * ## 四条硬约束（踩过，别改）
 *
 *  - **底色必须画在 `layerBackdrop` 之内**（见下面 ① 的长注释）。写在它外面 = 录到全透明的源
 *    = 既没有模糊、也不遮底（折射出重影）。这一条是最反直觉、也最容易写错的一条。
 *  - 玻璃组件必须是**录制盒的兄弟节点**，不能是自己采样自己的递归源 → `prepareTreeImpl` 无限递归 → SIGSEGV。
 *  - 页面内的玻璃（引导语砖）必须只采样 `baseBackdrop`。采样 `pageBackdrop` 会自采样。
 *  - 玻璃体只允许 [glassVeilBrush] 那一层"上透下灰"的霜面。不要往玻璃体里铺另一个实色灰块 ——
 *    纯白底叠一层均匀灰会读成"脏玻璃"（用户两次否过）。
 */

/** 背景层句柄。未提供时玻璃组件退化为普通 Modifier，不会崩。 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 背景样式。 */
enum class LotusBackgroundStyle {
  /**
   * **苹果风白底（默认）**。
   *
   * 不是一块死白：中心偏上有一团几乎察觉不到的柔光，四周极轻收边 ——
   * 这样玻璃"能糊到东西、有明暗可折"，白底上才立得起来。
   * 纯 `#FFFFFF` 平底会让玻璃完全隐形（用户："就算是纯白背景苹果也能一眼看出来"）。
   */
  Apple,

  /** 纯色（真·纯白，一点环境光都不给）。只留给需要绝对干净底的地方。 */
  Solid,

  /** 底色 + 对角极淡渐变（备用）。 */
  Gradient,
}

/**
 * 玻璃描边：最顶端一小段高光白，往下转浅灰，底边最深。
 *
 * 纯白底上这是玻璃"存在感"的主力 —— 参照定稿图采样：上沿 238~245、下沿 234。
 *
 * @param alpha 整体浓度。小控件（左上角圆钮）调低会更通透。
 */
internal fun glassRimBrush(alpha: Float = 1f): Brush =
  Brush.verticalGradient(
    // ⚠️ 这三段的比例是"白底上能不能一眼看出是玻璃"的关键，别随手调平。
    // 0.00 那一刀必须是**高 alpha 的纯白镜面**（苹果的 specular）。白底上白色高光本来是看不见的，
    // 它能被看见全靠下面 [glassVeilBrush] 把玻璃体压暗了几个百分点 —— 体暗、边亮，边才成立。
    0.00f to Color.White.copy(alpha = 0.92f * alpha),
    0.10f to Color.White.copy(alpha = 0.16f * alpha),
    // 中段：极淡的墨，给玻璃"厚度"。
    0.55f to LotusPalette.Ink.copy(alpha = 0.025f * alpha),
    // 下沿：接触阴影，最深 -> 玻璃"压在"画面上的那一笔。
    1.00f to LotusPalette.Ink.copy(alpha = 0.140f * alpha),
  )

/**
 * 玻璃**内**描边（v11 起所有玻璃边都走它，别再直接用 [androidx.compose.foundation.border]）。
 *
 * `Modifier.border` 是把线宽**骑在轮廓上**画的：一半在形状外、一半在内。圆角/胶囊上外侧那半
 * 会被抗锯齿啃成毛边，读起来就是"一圈脏线、双层边"——用户原话：「你怎么在一块大玻璃上把它
 * 描边描得这么丑」。内缩半个线宽之后，边永远落在实心区域内，弧线是干净的。
 */
internal fun Modifier.glassRim(
  shape: Shape,
  alpha: Float = 1f,
  width: Dp = 1.dp,
  // 用户已明确否掉整圈彩虹描边。色散只允许由真实 lens 在背后有内容时自然产生，
  // 不再额外画一圈 sweep spectrum。
  spectrum: Float = 0f,
): Modifier =
  drawWithContent {
    drawContent()
    // ---- "采光"：边缘一圈极淡的光谱（色散）----
    //
    // 用户口径：「我要是彩色那种，在"思考和模型选择"那里，边缘会出现光」——
    // 面板边缘那道彩光是 `lens(chromaticAberration = true)` 折出来的，**但它只在背后有内容时**
    // 才看得见（纯白上折射白还是白）。所以这里补一层显式的 sweep 光谱描边：
    // 无论背景是什么，每块玻璃的边缘都自带那一圈彩色光，一眼能认出是玻璃。
    // alpha 必须压得很低（0.16）——再高就从"光"变成"彩色描边"了。
    if (spectrum > 0f) {
      val sStroke = width.toPx() * 1.6f
      val sInset = sStroke / 2f
      val sw = (size.width - sStroke).coerceAtLeast(0f)
      val sh = (size.height - sStroke).coerceAtLeast(0f)
      if (sw > 0f && sh > 0f) {
        val spectral =
          Brush.sweepGradient(
            colors =
              listOf(
                  Color(0xFFFF4D6A),
                  Color(0xFFFFC24D),
                  Color(0xFF6BE39A),
                  Color(0xFF4DC8FF),
                  Color(0xFF9B8CFF),
                  Color(0xFFFF4D6A),
                )
                .map { it.copy(alpha = spectrum) },
            center = Offset(size.width / 2f, size.height / 2f),
          )
        val sOutline = shape.createOutline(Size(sw, sh), layoutDirection, this)
        val sStyle = Stroke(sStroke)
        translate(sInset, sInset) {
          when (sOutline) {
            is Outline.Rounded ->
              drawRoundRect(
                brush = spectral,
                topLeft = Offset(sOutline.roundRect.left, sOutline.roundRect.top),
                size = Size(sOutline.roundRect.width, sOutline.roundRect.height),
                cornerRadius = sOutline.roundRect.topLeftCornerRadius,
                style = sStyle,
              )
            is Outline.Generic ->
              drawPath(path = sOutline.path, brush = spectral, style = sStyle)
            else -> drawRect(brush = spectral, style = sStyle)
          }
        }
      }
    }
    val stroke = width.toPx()
    val inset = stroke / 2f
    val w = (size.width - stroke).coerceAtLeast(0f)
    val h = (size.height - stroke).coerceAtLeast(0f)
    if (w <= 0f || h <= 0f) return@drawWithContent
    val brush = glassRimBrush(alpha)
    val style = Stroke(stroke)
    val outline = shape.createOutline(Size(w, h), layoutDirection, this)
    translate(inset, inset) {
      when (outline) {
        is Outline.Rounded ->
          drawRoundRect(
            brush = brush,
            topLeft = Offset(outline.roundRect.left, outline.roundRect.top),
            size = Size(outline.roundRect.width, outline.roundRect.height),
            cornerRadius = outline.roundRect.topLeftCornerRadius,
            style = style,
          )
        is Outline.Rectangle ->
          drawRect(
            brush = brush,
            topLeft = Offset(outline.rect.left, outline.rect.top),
            size = Size(outline.rect.width, outline.rect.height),
            style = style,
          )
        is Outline.Generic -> drawPath(path = outline.path, brush = brush, style = style)
      }
    }
  }

/**
 * 玻璃的"霜面"（frost veil）。
 *
 * ## 为什么必须有它（这条推翻了"纯透才是好玻璃"的直觉）
 *
 * `blur()` 模糊的是玻璃**后面**的东西。首页那块画布是纯白，玻璃后面也是纯白 ——
 * `blur(白)` 还是白，`vibrancy(白)` 还是白。**纯白玻璃压在纯白画布上，物理上就是不可见的。**
 * 这就是用户两次说的"输入框都还是没有模糊效果"：不是模糊没接上（它接上了，键盘抬起压住
 * 引导语时是能看见糊开的），而是**没东西可糊的时候，玻璃就退化成了一条描边**。
 *
 * Apple 的做法也一样：Liquid Glass 一定带一层极淡的磨砂，**上沿几乎全透、下沿收一点灰**，
 * 玻璃才有"面"。所以这里给一个 0 → 2% → 7% 的竖向渐变：
 *
 *  - 顶部 `Transparent` = 保住通透感（别再往玻璃体里铺实色，那条路用户已经否过：会灰）；
 *  - 底部 7% Ink = 唯一的那点"厚度"，纯白底上正好读成一块磨砂玻璃而不是一块脏灰块。
 *
 * @param scale 1 = 标准浮层；左上角那类小圆钮用 0.5 左右更透。
 */
internal fun glassVeilBrush(scale: Float = 1f): Brush =
  Brush.verticalGradient(
    // ⚠️ 顶部**不再是全透**。旧配方是 0% -> 2% -> 7%，只有下沿有灰：白底上读出来就是
    // 「一块白板 + 下沿一条阴影」，不是玻璃（用户原话：「背景不透…只有选中的地方有一点点折射」）。
    // 苹果的 regular 变体在 HIG 里的定义是 "blurs and **adjusts the luminosity** of background
    // content" —— 亮背景主动压暗，这是材质层，不是 bug。纯白 #FFFFFF 是亮度天花板，白高光在白底上
    // 不可能更亮，所以"看得见"只能靠体色比画布暗几个百分点换来。
    0.00f to LotusPalette.Ink.copy(alpha = 0.016f * scale),
    0.55f to LotusPalette.Ink.copy(alpha = 0.030f * scale),
    1.00f to LotusPalette.Ink.copy(alpha = 0.052f * scale),
  )

/**
 * 苹果风白底的"环境光" —— 一块几乎全是白的底，但有两件事在发生：
 *
 *  1. **中心偏上一团极淡的柔光**：模拟室内/天光从上方打下来，画面是"亮的"，
 *     所以它是 [Color.White]（比底还亮），不是加灰；
 *  2. **四周极轻的收边**：边缘比中心低 2~3 个色阶，白底因此有了"纵深"，
 *     玻璃压在它上面时，外圈那道柔影读得出来。
 *
 * ⚠️ 这两层的浓度都**刻意压到肉眼几乎察觉不到**（Δ ≤ 4/255）。放大任何一层都会：
 *  · 变成"灰蒙蒙的脏玻璃"（用户两次否过）或变成明显渐变（用户否过"不要渐变/紫色"）。
 *
 * ⚠️ 底本身**不能是纯 `#FFFFFF`** —— 白加白等于什么都没发生，玻璃在白底上会完全隐形。
 * 底取 [LotusPalette.Canvas]（比纯白低几个色阶的冷白），柔光才有对比可加。
 */
@Composable
private fun AppleAmbientLight() {
  Canvas(modifier = Modifier.fillMaxSize()) {
    // ① 顶部柔光：以画面上方 34% 处为圆心，一路白到透明。
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(Color.White, Color.Transparent),
          center = Offset(size.width * 0.50f, size.height * 0.34f),
          radius = size.maxDimension * 0.86f,
        )
    )
    // ② 四周收边：中段全透明、边缘一层极淡的墨，给白底一点"球面感"。
    drawRect(
      brush =
        Brush.radialGradient(
          colors = listOf(Color.Transparent, LotusPalette.Ink.copy(alpha = 0.022f)),
          center = Offset(size.width * 0.50f, size.height * 0.42f),
          radius = size.maxDimension * 0.80f,
        )
    )
  }
}

/**
 * 全屏玻璃背景层。
 *
 * @param page 页面内容（会被录进 backdrop，玻璃悬浮层能模糊到它）。
 * @param content 悬浮玻璃层（输入条 / 底栏 / 顶栏玻璃钮）。
 */
@Composable
fun GlassBackground(
  modifier: Modifier = Modifier,
  baseColor: Color = LotusPalette.Canvas,
  accent: Color = LotusPalette.Ink,
  style: LotusBackgroundStyle = LotusBackgroundStyle.Apple,
  page: @Composable BoxScope.() -> Unit = {},
  content: @Composable BoxScope.() -> Unit,
) {
  val baseBackdrop = rememberLayerBackdrop()
  val pageBackdrop = rememberLayerBackdrop()

  Box(modifier = modifier.fillMaxSize()) {
    // ① 纯色底
    //
    // ⚠️⚠️ 底色**必须画在 `layerBackdrop` 之内**。这一条是反编译库源码才确认的，别再"顺手优化"回
    // `Modifier.background(color).layerBackdrop(backdrop)` 那个写法 —— 那个写法看着等价，其实是错的。
    //
    // 库的实现（`LayerBackdropNode.draw`）：
    //   draw(scope) { scope.drawContent(); record { scope.drawContent() } }
    // `drawContent()` 只画**本 modifier 之内**的内容。`.background()` 写在 `.layerBackdrop()` 前面
    // 就是**外面** ⇒ 录进那份纹理的是**全透明**（Solid 分支下面什么都不画）。
    //
    // 全透明的源会同时毁掉两件事：
    //   1. `blur()` / `vibrancy()` 对透明毫无作用 ⇒ 玻璃"没有面"，也没得可糊（用户："为什么不能全局模糊"）；
    //   2. 玻璃不遮底 ⇒ 折射出的图标叠在原来那枚图标上 ⇒ **残影**（用户："倒是把残影给装上了"）。
    Box(modifier = Modifier.fillMaxSize().layerBackdrop(baseBackdrop)) {
      Box(modifier = Modifier.fillMaxSize().background(baseColor)) {
        when (style) {
          LotusBackgroundStyle.Apple -> AppleAmbientLight()
          LotusBackgroundStyle.Gradient ->
            Canvas(modifier = Modifier.fillMaxSize()) {
              drawRect(
                brush =
                  Brush.linearGradient(
                    colors =
                      listOf(accent.copy(alpha = 0.06f), Color.Transparent, accent.copy(alpha = 0.03f)),
                    start = Offset(size.width * 0.10f, 0f),
                    end = Offset(size.width * 0.90f, size.height),
                  )
              )
            }
          LotusBackgroundStyle.Solid -> Unit
        }
      }
    }

    // ②③ 页面内容 + 悬浮玻璃层 **必须放在同一个 Box 里**。
    //
    // ⚠️ 以前这里是把 `page()` 当成外层 Box 的**兄弟节点**、content 再当第三个兄弟。那份写法
    // 页面能画出来，但**整块收不到触摸**（点不动、拖动也不行、无障碍树里也只剩一个空壳）——
    // 设置页所有行都点不动就是这个原因（实测：底栏/输入条所在的 content 槽一切正常）。
    // 改成"页面是 content 层的第一个子节点（最底下）"之后，页面和那些能点的控件**在同一棵
    // 可命中的子树里**，命中路径才完整。
    Box(modifier = Modifier.fillMaxSize()) {
      // 页面内容：录进 pageBackdrop。内部玻璃只采样纯色底，避免自采样。
      CompositionLocalProvider(LocalGlassBackdrop provides baseBackdrop) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) { page() }
      }

      // 悬浮玻璃层：采样 base ∪ page。
      CompositionLocalProvider(
        LocalGlassBackdrop provides rememberCombinedBackdrop(baseBackdrop, pageBackdrop)
      ) {
        content()
      }
    }
  }
}

/**
 * ## 全 App 的液态玻璃配方（2026-10-11 定稿，用户点名"以后 UI 都用这种"）
 *
 * 用户认可的参照图 = 一张模型切换面板：**背后内容还看得清轮廓（轻模糊）、边缘一圈明显折射、
 * 边角带色散彩边、外圈一道白高光**。要复现它必须把两件事**分开**：
 *
 *  - [LiquidBlurScale] 0.32 —— 模糊只留一点点雾。取 1 就是普通高斯毛玻璃（用户明确否过）。
 *  - [LiquidLensScale] 1.5 —— 折射比模糊重得多，玻璃的"鼓面"从边缘长出来。
 *
 * ⚠️ 别再让模糊和折射共用一个系数。上一版就是 `blur = 16dp×strength`、`lens = 20dp×strength`，
 * 想加强折射就只能连模糊一起抬，一抬就到 16dp 高斯 —— 那不是设计选择，是参数缺陷。
 */
const val LiquidBlurScale: Float = 0.32f
const val LiquidLensScale: Float = 1.5f

/**
 * 玻璃面板 Modifier。
 *
 * 绘制顺序（**别调**）：
 *  1. `shadow` —— 面板外的柔影。
 *  2. `drawBackdrop` —— 折射 / 模糊 / 色散 / vibrancy。**这几样一个都不能砍**：
 *     砍了 blur 就没模糊，砍了 lens 就没扭曲（两个都被用户点名要求过）。
 *  3. `border(glassRimBrush())` —— 上白下灰的玻璃边。
 *
 * @param strength 0..1，越大折射/模糊越强。芯片 0.3~0.45，输入条 0.5，浮层 0.6+。
 * @param tint 额外叠色。**默认 null**：白底上实色会把玻璃变成灰块。
 * @param elevation 外投影高度。0 = 不要影子。
 * @param rimAlpha 描边浓度。越小越通透（左上角圆钮用过）。
 * @param veil 霜面浓度。1 = 标准浮层；0.5 更透；0 = 完全不要霜面（只在深色画布上才该这么干）。
 * @param blurScale 模糊倍率（相对 strength）。**液态玻璃取 0.25~0.4**：背后内容还看得清轮廓，
 *   只有一层雾。取 1 就是普通高斯毛玻璃 —— 用户明确否过。
 * @param lensScale 折射倍率（相对 strength）。**边缘玻璃感靠它**，液态玻璃取 1.3~1.8。
 */
@Composable
fun Modifier.glassPanel(
  shape: Shape,
  strength: Float = 0.55f,
  tint: Color? = null,
  elevation: Dp = 7.dp,
  rimAlpha: Float = 0.55f,
  veil: Float = 1f,
  blurScale: Float = LiquidBlurScale,
  lensScale: Float = LiquidLensScale,
  backdrop: Backdrop? = LocalGlassBackdrop.current,
  exportedBackdrop: LayerBackdrop? = null,
): Modifier {
  if (backdrop == null) return this
  val density = LocalDensity.current
  // 模糊与折射**分开调**：液态玻璃的观感是"背后几乎看得清（轻模糊）+ 边缘一圈明显折射"，
  // 不是整块高斯模糊。原来两者死绑在同一个 strength 上，要折射就得一起糊 —— 那正是
  // 用户否掉的"糊成高斯模糊"。
  val blurPx = with(density) { (16f * strength * blurScale).dp.toPx() }
  val lensPx = with(density) { (20f * strength * lensScale).dp.toPx() }
  return this
    .shadow(
      elevation = elevation,
      shape = shape,
      clip = false,
      ambientColor = Color(0x12000000),
      spotColor = Color(0x1C000000),
    )
    .drawBackdrop(
      backdrop = backdrop,
      shape = { shape },
      exportedBackdrop = exportedBackdrop,
      effects = {
        vibrancy()
        if (blurPx > 0f) blur(blurPx)
        if (lensPx > 0f) {
          // 折射带**比玻璃尺寸窄**：能量集中在边缘一圈，中间保持清澈。
          // 第一参 = 边缘折射带的高度，第二参 = 位移量（越大越"玻璃鼓起来"）。
          lens(lensPx * 0.62f, lensPx * 1.15f, chromaticAberration = true)
        }
      },
      // 镜面高光：上游默认只有 0.5dp 宽 / 0.25dp 模糊。加宽到 1dp 才读得出"玻璃边缘"那道光。
      highlight = { Highlight.Default.copy(width = 1.dp, alpha = 0.90f) },
      shadow = { Shadow(alpha = 0f) },
      // 恒 0：内投影画在玻璃内部，浅色底上会变成中央一块灰。
      innerShadow = { InnerShadow(radius = 0f.dp, alpha = 0f) },
      onDrawSurface = {
        // 霜面：玻璃在纯白底上唯一的"面"。见 [glassVeilBrush] 的说明。
        if (veil > 0f) drawRect(brush = glassVeilBrush(scale = veil))
        tint?.let { drawRect(it) }
      },
    )
    .glassRim(shape = shape, alpha = rimAlpha)
}

/**
 * **浮层玻璃统一配方** —— 所有"飘在页面上面"的玻璃（弹层 / 长按面板 / 菜单 / 确认框 /
 * 输入条上的小片子）都走它。2026-10-11 定。
 *
 * ## 为什么必须统一（用户报的问题）
 *
 * 原话：「现在有两种不同风格的玻璃，把那一块全部统一了。这种二级弹窗的玻璃太丑了，
 * 在纯白模式下显示不出来也不对，**它的颜色竟然跟背景不一样**。」
 *
 * 复盘：当时 11 个调用点各传各的 —— `tint` 从 `White 0.22` 到 `White 0.42` 五种，
 * `rimAlpha` 0.5~0.8，`strength` 0.34~0.85。白霜叠得重的那些（0.42）在纯白底上就成
 * **一块白板**；而弹层底下又压着一层 12% 黑的 scrim，玻璃采样的是**没被压暗的页面**，
 * 于是弹层比周围亮一截 —— 看起来就像"贴上去的一张白纸"，这就是"颜色跟背景不一样"。
 *
 * 统一后的规则：
 *  1. **不发白霜**（`tint` 默认 null）。玻璃的"面"只由 [glassVeilBrush] 那层亮度微调给；
 *  2. 描边浓度全部 = [FloatingGlassRimAlpha]（0.55），不再一个一个调；
 *  3. 底下有 scrim 的模态弹层，用 [dim] 把自身压暗到跟被压暗的页面同一个亮度 ——
 *     亮度接上了，"颜色不一样"才真正消失。
 *
 * @param dim 自身压暗量。**只在底下有 scrim 时用**，取值跟 scrim 的 alpha 对齐（scrim 0.10 → dim 0.09）。
 */
const val FloatingGlassRimAlpha: Float = 0.55f

/**
 * 浮层玻璃。见 [FloatingGlassRimAlpha] 的说明。
 *
 * @param strength 越大折射/模糊越强。芯片 0.5、面板 0.55~0.6、模态弹层 0.7。
 * @param dim 自身压暗（配 scrim 用）。
 */
@Composable
fun Modifier.floatGlass(
  shape: Shape,
  strength: Float = 0.6f,
  elevation: Dp = 16.dp,
  veil: Float = 1f,
  dim: Float = 0f,
  blurScale: Float = LiquidBlurScale,
): Modifier =
  glassPanel(
    shape = shape,
    strength = strength,
    // ⛔ 不要在这里加白霜。白底上的"白板"就是它造成的。
    tint = if (dim > 0f) Color.Black.copy(alpha = dim) else null,
    elevation = elevation,
    rimAlpha = FloatingGlassRimAlpha,
    veil = veil,
    blurScale = blurScale,
  )

/**
 * **嵌套玻璃（寄生玻璃）** —— 给"已经站在另一块玻璃上"的按键用。
 *
 * ## 铁律（2026-10-11 定，别再违反）
 *
 * 现实里两块玻璃叠在一起，是**看不出多出来一圈边**的。所以嵌在大玻璃内部的按键
 * **不许再画自己的玻璃壳** —— 不描边、不霜面、不投影，静止时在眼睛里就是"不存在的"。
 *
 * 但它们必须是**真按键**：按下时玻璃感才浮出来（折射 + 边缘高光 + 手指那点光），
 * 抬手立刻回去。这样既没有"玻璃叠玻璃"的脏边，又保留了"指哪点哪"的反馈。
 *
 * @param active 按压强度 0..1。**必须由调用方用按下的动画驱动** —— 0 = 完全隐形，
 *   1 = 浮出一层薄玻璃。传死值就废了（静止常显 = 又变成第二块玻璃）。
 */
@Composable
fun Modifier.glassInline(
  shape: Shape,
  strength: Float = 0.5f,
  active: Float = 0f,
  lensScale: Float = LiquidLensScale,
  dark: Boolean = false,
  backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
  if (backdrop == null) return this
  val a = active.coerceIn(0f, 1f)
  if (a <= 0.001f) return this
  val density = LocalDensity.current
  val blurPx = with(density) { (16f * strength * LiquidBlurScale).dp.toPx() * a }
  val lensPx = with(density) { (20f * strength * lensScale).dp.toPx() * a }
  return this
    // ---- ⚠️ 2026-10-16：按下必须**看得见**，不能只靠折射 ----
    //
    // 用户口径：「设置的时候这些按键……按了之后没任何反应，这就很无力」「反馈感的话，
    // 就像玻璃拉伸那种」。实测确认：这一版以前按下只做「折射背景 + 一圈白描边」，
    // 而设置页 / MD 页拿到的 backdrop 是**纯白底色** —— 没东西可折射，白边压在白底上
    // 也看不见，最后只剩 2% 缩放，肉眼就是"按了没反应"。
    //
    // 所以按下时让这块玻璃**真的离开纸面一点点**，三样都跟按压强度 a 走：
    //   ① 投影（Modifier.shadow）：抬起来才有影子，白底上最读得出来；
    //   ② 玻璃体（glassVeilBrush）：见它头部那段 —— 纯白底上白高光不可能更亮，
    //      只有把体色压暗几个百分点，边上那圈镜面高光才成立；
    //   ③ 镜面描边（glassRim）：苹果那圈 specular。
    // ⛔ 依然不许用"整块变灰"当反馈（那是用户否掉的传统 ripple）。
    .shadow(
      elevation = PressLift * a,
      shape = shape,
      clip = false,
      ambientColor = Color(0x14000000),
      spotColor = Color(0x1F000000),
    )
    .drawBackdrop(
      backdrop = backdrop,
      shape = { shape },
      effects = {
        vibrancy()
        if (blurPx > 0f) blur(blurPx)
        if (lensPx > 0f) lens(lensPx * 0.78f, lensPx * 1.5f, chromaticAberration = true)
      },
      highlight = { Highlight.Default.copy(width = 1.dp, alpha = 0.90f * a) },
      shadow = { Shadow(alpha = 0f) },
      innerShadow = { InnerShadow(radius = 0f.dp, alpha = 0f) },
      onDrawSurface = {
        if (dark) {
          // 暗色终端：不能再用压暗霜面，否则越按越黑。保持同一套折射，只换成
          // 极轻的白色体色和更亮的边缘采光，让按键状态在深色背景上读得出来。
          drawRect(Color.White.copy(alpha = 0.12f * a))
        } else {
          drawRect(brush = glassVeilBrush(scale = a))
        }
      },
    )
    .glassRim(shape = shape, alpha = (if (dark) 1.15f else 0.9f) * a)
}

/** 按下时玻璃"抬起"多少。见 [glassInline] 里那段说明：白底上没投影就等于没反馈。 */
private val PressLift = 7.dp

/**
 * **行级按压反馈** —— 列表里那种"整行都是按键"的地方用它。
 *
 * ## 为什么要有它（用户 2026-10-11 报的真问题）
 *
 * 原话：「我在设置页和文件页长按的时候，它怎么还有一整块灰的盖住啊，这不应该是放大
 * 或者是那个吗，就不要弄传统的操作逻辑了。」
 *
 * 那块灰是 `drawWithContent { drawRect(Ink.copy(alpha = 0.055f)) }` —— Android 老式列表的
 * rippl 手感，和液态玻璃不是一套语言，而且它是**直角矩形**，压在圆角玻璃上格外脏。
 *
 * 现在按下只做两件事，和 [LiquidGlassIconButton] 同一套：
 *  1. **轻微放大**（默认 1.02。整行很大，用按钮那套 1.13 会糊出格子）；
 *  2. **浮出一层薄折射 + 高光**（[glassInline]）。
 *
 * ⛔ 不要在这些行上再加任何"变灰"的反馈。
 */
@Composable
fun Modifier.pressLiquid(
  active: Float,
  shape: Shape,
  scale: Float = 1.02f,
  // ⚠️ 0.34 太弱：按下去只剩"一道光"，看不出玻璃被扭曲（用户在设置页逐行按过一遍）。
  strength: Float = 0.62f,
  dark: Boolean = false,
  backdrop: Backdrop? = LocalGlassBackdrop.current,
): Modifier {
  val a = active.coerceIn(0f, 1f)
  val s = 1f + (scale - 1f) * a
  return this
    .graphicsLayer {
      scaleX = s
      scaleY = s
    }
    .glassInline(shape = shape, strength = strength, active = a, dark = dark, backdrop = backdrop)
}

/**
 * **列表行 / 面板行的按压反馈** —— 把 [pressLiquid] 那一套（按下放大 + 浮出一层薄折射 +
 * 抬手回弹）收成一个可以整行套上的对象。
 *
 * 为什么要有它：面板里的行以前只写了 `.clickable`，**按下去一点动静都没有** ——
 * 用户口径：「每个按下去都有动态效果那些都有没有」「其他按键现在也是死的」。
 *
 * 用法：
 * ```
 * val p = rowPress(RoundedCornerShape(14.dp))
 * Row(
 *   modifier = Modifier.fillMaxWidth()
 *     .then(p.modifier)                                   // ← 必须在 clickable 之前
 *     .clickable(interactionSource = p.interaction, indication = null) { … }
 * ) { … }
 * ```
 *
 * ⛔ 行是**嵌在大玻璃里面**的，静止时必须完全隐形：[pressLiquid] 只在 active > 0 时才画玻璃，
 *    所以这里**不许**再叠 `.background()` / `.border()` / 描边 —— 那就退化成"第二块玻璃 +
 *    多出来的一圈边"（用户点名要删的那种）。
 */
class RowPress internal constructor(
  /** 交给行的 `.clickable(interactionSource = …, indication = null)`。 */
  val interaction: MutableInteractionSource,
  /** 串在行 Modifier 上，**必须在 clickable 之前**。 */
  val modifier: Modifier,
)

@Composable
fun rowPress(
  shape: Shape,
  scale: Float = 1.03f,
  strength: Float = 0.34f,
  dark: Boolean = false,
): RowPress {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val press by
    animateFloatAsState(
      targetValue = if (pressed) 1f else 0f,
      animationSpec = lotusSpring(LotusSpringPreset.Snappy),
      label = "rowPress",
    )
  return RowPress(
    interaction = interaction,
    modifier = Modifier.pressLiquid(
      active = press,
      shape = shape,
      scale = scale,
      strength = strength,
      dark = dark,
    ),
  )
}

/** 玻璃容器：把 [glassPanel] 包成可以直接摆内容的 Box。 */
@Composable
fun GlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape,
  strength: Float = 0.55f,
  tint: Color? = null,
  elevation: Dp = 7.dp,
  rimAlpha: Float = 0.55f,
  veil: Float = 1f,
  blurScale: Float = LiquidBlurScale,
  lensScale: Float = LiquidLensScale,
  contentAlignment: Alignment = Alignment.Center,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier =
      modifier.glassPanel(
        shape = shape,
        strength = strength,
        tint = tint,
        elevation = elevation,
        rimAlpha = rimAlpha,
        veil = veil,
        blurScale = blurScale,
        lensScale = lensScale,
      ),
    contentAlignment = contentAlignment,
    content = content,
  )
}

@Composable
internal fun LiquidGlassBackdrop(
    modifier: Modifier = Modifier,
    page: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    Box(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            page()
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Box(modifier = Modifier.fillMaxSize()) {
                overlay()
            }
        }
    }
}
