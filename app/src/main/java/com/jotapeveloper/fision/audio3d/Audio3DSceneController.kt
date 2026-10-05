package com.jotapeveloper.fision.audio3d

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.filament.Colors
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneView
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.toColor
import io.github.sceneview.texture.setBitmap
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.ImageNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One room plane: build shape + final pose. */
private data class PlaneSpec(
    val size: Size,
    val center: Position,
    val rotation: Rotation,
    val normal: Direction,
    val material: MaterialInstance?
)

/**
 * Bring-up controller for the 3D Audio scene (Filament via SceneView).
 * Owns the listener head, a grey cubicle around it, stem sprites with
 * floor shadows and selection rings, touch handling with offset drag +
 * long-press-to-lift, and a "lights off" fade of the scene illumination.
 */
class Audio3DSceneController(
    private val activity: Activity,
    private val sceneView: SceneView
) {

    companion object {
        private const val TAG = "Audio3DScene"
        private const val HEAD_ASSET = "models/head.glb"
        // Default orbital pose. Behind the head (az=180°), looking down at
        // 30° so the floor layout of the stems reads at a glance. Values
        // captured live with the debug tuning panel.
        private const val DEF_AZIMUTH_DEG = 179.7f
        private const val DEF_PITCH_DEG   = 30.0f
        private const val DEF_DISTANCE    = 4.36f
        // Lens focal length (mm, 35 mm-film equivalent; SceneView default
        // 28). Wider than the default: on a portrait screen 28 mm left only
        // ~26° horizontally and the room read as a tunnel.
        private const val CAMERA_FOCAL_MM = 19.1

        // Room geometry (metres): 9 x 9 x 3.8, studio-sized. Stems reach
        // 3 m from the head, leaving ~1.5 m to the walls. The camera may
        // leave the room: walls hide when crossed (dollhouse), so the room
        // no longer has to be oversized for the orbit. Head sits on a
        // pedestal that reaches from the floor to HEAD_CENTER_Y.
        private const val ROOM_HALF_X    = 4.5f
        private const val ROOM_FLOOR_Y   = -1.4f
        private const val ROOM_CEIL_Y    =  2.4f
        private const val ROOM_HALF_Z    = 4.5f
        // Top surface of the floor slab (slab is 0.06 thick, centered at
        // ROOM_FLOOR_Y + 0.03). Models seat on this plane, not on the room
        // cube bottom, or their base visibly sinks into the slab.
        private const val ROOM_FLOOR_SURFACE_Y = ROOM_FLOOR_Y + 0.06f
        // Lowest allowed camera height: chest level of the dummy. The
        // camera can never orbit below this plane.
        private const val CHEST_Y        = ROOM_FLOOR_SURFACE_Y + 1.2f
        // Dollhouse hysteresis: a room surface hides once the camera is
        // HIDE_EPS past its plane and only reappears once the camera is
        // SHOW_EPS back inside — the band stops flicker at the boundary.
        private const val SURFACE_HIDE_EPS = 0.05f
        private const val SURFACE_SHOW_EPS = 0.15f
        // Deepest wall fixture (window sill, 0.22) plus camera near-plane
        // margin. Walls toggle at this distance inside their plane.
        private const val WALL_FIXTURE_CLEARANCE = 0.35f
        // Head model scale: makes the loaded glb fit ~1.8 m tall so the
        // included stand/base sits on the floor and the ear line lands
        // near HEAD_CENTER_Y.
        private const val HEAD_MODEL_SCALE_UNITS = 1.8f
        // Y where sprites orbit (approximate ear line). With the base of
        // the model on ROOM_FLOOR_Y = -1.4 and total height 1.8, the ears
        // sit near the top of the head — around 85% up the model.
        private const val HEAD_CENTER_Y  = 0.15f

        // Sprite size (in scene units — head is ~1u wide).
        private const val SPRITE_SIZE      = 0.5f
        // Selection ring: drawn as an ImageNode slightly larger than the
        // sprite, centered on the same world position (billboarded too).
        private const val RING_SIZE        = SPRITE_SIZE * 1.55f
        // Omnipresent halo: soft accent glow behind the sprite, larger than
        // the selection ring so the two states never read as the same mark.
        private const val OMNI_SIZE        = SPRITE_SIZE * 2.0f
        // Ground shadow: soft dark disc under each sprite. Cheap depth cue.
        private const val SHADOW_SIZE      = SPRITE_SIZE * 1.1f
        // Height above the floor at which a sprite shadow reaches its
        // widest / faintest tier.
        private const val SHADOW_TIER_HEIGHT = 4f
        // Pre-baked shadow tiers: enough steps that the fade reads as
        // continuous while dragging a stem up and down.
        private const val SHADOW_TIER_COUNT = 8

        // Distance clamps (metres) shared by drag and the distance slider.
        private const val MIN_DISTANCE     = 0.3f
        private const val MAX_DISTANCE     = 3.0f

        // Height clamp (metres relative to the head centre): keep sprites
        // inside the cubicle, a margin above the floor and below the ceiling.
        private const val H_MIN_M = (ROOM_FLOOR_Y + 0.20f) - HEAD_CENTER_Y
        private const val H_MAX_M = (ROOM_CEIL_Y - 0.20f) - HEAD_CENTER_Y

        // Sprite long-press window (mute). Matches the flat-mode value.
        private const val LONG_PRESS_MS    = 450L
        // Reveal gate (runWhenWarm): minimum rendered frames and minimum
        // time since setup — past the 500ms cold-start IBL re-apply.
        private const val WARM_MIN_FRAMES  = 6
        private const val WARM_MIN_MS      = 600L
        // Own double-tap window (omnipresent): two clean taps within this
        // time and radius of each other.
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val DOUBLE_TAP_RADIUS_DP  = 60f

        // Sine-wave dance amplitude (metres) around each stem's base height.
        private const val DANCE_SINE_AMP = 0.6f
        const val DEFAULT_OSC_SPEED = 0.5f

        // Engine update throttle. 30 fps is plenty for spatialisation and
        // halves the JNI + atomic traffic vs the render tick (60 fps).
        private const val ENGINE_UPDATE_INTERVAL_NS = 33_000_000L  // ~30 fps

        // Sprite yaw refresh: only rebuild if the camera moved more than
        // this many world units since the last orient pass.
        private const val CAM_MOVE_EPS = 0.0025f

        // Illumination levels for "lights on / off". OFF stays around 20%
        // so the room silhouette is still readable — going lower reads
        // as broken rather than as a dimmed room.
        private const val LIGHT_INTENSITY_ON  = 1f
        private const val LIGHT_INTENSITY_OFF = 0.20f

        // Per-lamp intensity for the ceiling corner point lights. Filament
        // POINT lights use lumens. Tuned at 60k for the former 12 m room;
        // lamps and surfaces scale with the room, so illuminance grows with
        // 1/scale² — 60k × 0.75² keeps the same look in the 9 m room.
        private const val LAMP_INTENSITY      = 34_000f
        // Share of the SceneView default IBL (environment fill) kept in the
        // room. The IBL is calibrated for daylight at the sunny-16 exposure,
        // so at full strength it drowns the lamps' contribution.
        private const val AMBIENT_FACTOR      = 1f

        // Fallback drag sensitivity when the raycast against the sphere
        // misses (finger outside the projected sphere on screen). Tuned
        // so a full-screen swipe covers roughly half a turn.
        private const val DRAG_FALLBACK_RAD_PER_PX = 0.006f

        // Fallback drag sensitivity for the height component (meters per px).
        private const val DRAG_FALLBACK_M_PER_PX = 0.003f

        // Studio furniture: matte acoustic panel tiles on the three
        // interior walls and a bass-trap cylinder in each corner. Static
        // decoration — no per-frame updates. Panels are children of their
        // wall plane so the dollhouse toggle hides them along with it;
        // bass traps are children of the scene root but their visibility
        // mirrors their two adjacent walls.
        // Grid dense enough that the panels read as a wall covering rather
        // than a stamp in the middle of an empty plane (wall is 9x3.8 m).
        private const val PANEL_W = 0.6f
        private const val PANEL_H = 0.6f
        private const val PANEL_D = 0.03f
        private const val PANEL_COLS = 6
        private const val PANEL_ROWS = 4
        private const val PANEL_COL_SPACING_X = 1.35f
        private const val PANEL_ROW_SPACING_Y = 0.80f
        // Protrusion of the panel center from the wall face (panel is
        // PANEL_D thick), keeping it clear of the wall plane (no z-fight).
        private const val PANEL_OFFSET_Z = 0.025f
        private const val TRAP_RADIUS = 0.15f

        // Wall-mounted song card ("TV" screen) on the back wall in front
        // of the bust. A square bezel (matte black CubeNode) frames a square
        // ImageNode matching the artcover aspect; its bitmap is refreshed
        // only when the song metadata changes — the panel is deliberately
        // static so it never contributes to the per-frame budget.
        // The centre 2-cols × top-3-rows block of the back wall's panel grid
        // is skipped in addRoom to open the hole for it; the bottom row of
        // panels stays under the screen. Wall centre is world Y=0.5, so
        // local 0.4 → world 0.90 (bezel top 1.9, backlight top 2.35, under
        // the 2.4 ceiling).
        private const val SCREEN_LOCAL_Y = 0.40f
        // Square image plus a uniform 0.06 bezel margin on every side.
        private const val SCREEN_IMG_SIZE = 1.88f
        private const val SCREEN_BEZEL_SIZE = 2.00f
        private const val SCREEN_BEZEL_D = 0.06f
        private const val SCREEN_BITMAP_SIZE = 1024

        // TV backlight (ambient bias lighting): unlit glow plane on the back
        // wall behind the bezel, colored per side from the artwork edges.
        private const val BACKLIGHT_SIZE = 2.89f         // metres, square
        private const val BACKLIGHT_Z = 0.004f           // wall-local, behind the bezel (0.005)
        private const val BACKLIGHT_BITMAP = 256
        private const val BACKLIGHT_PEAK_ALPHA = 0.78f
        private const val BACKLIGHT_FALLOFF = 0.68f      // of the reach beyond the bezel
        // Rounded-square glow shape (corner radius as a fraction of the
        // bezel half-size) and a final box blur (px, 2 passes) so the halo
        // reads as diffuse light, not a drawn shape.
        private const val BACKLIGHT_CORNER = 0.45f
        private const val BACKLIGHT_BLUR_PX = 14

        // Room surface textures (tileable procedural fields, see fieldFor).
        // Values picked live with the debug tuning panel: walls + ceiling =
        // felt, floor = loop-pile carpet, panels and corner traps = cork.
        // One tile covers TILE_M metres; CONTRAST scales the field deviation;
        // tints come from wallTintFor / floorTintFor / ceilingTintFor.
        // 256 px matches the preview: the field frequencies are per tile, so
        // a larger bake would halve the per-pixel grain.
        private const val SURFACE_TEX_SIZE = 256
        private val WALL_PATTERN = SurfacePattern.FELT
        private val FLOOR_PATTERN = SurfacePattern.CARPET_LOOP
        // Acoustic panels and corner bass traps: own fixed colors
        // (PANEL_COLOR / TRAP_COLOR), wall-pattern catalogue.
        private val PANEL_PATTERN = SurfacePattern.CORK
        private val TRAP_PATTERN = SurfacePattern.CORK
        private val PANEL_COLOR = Color.rgb(40, 42, 46)
        private val TRAP_COLOR = Color.rgb(30, 31, 34)
        // Texture rotation (degrees) on walls/ceiling and floor: breaks the
        // alignment of the repeat with the room edges.
        private const val WALL_ROTATION_DEG = 132f
        private const val FLOOR_ROTATION_DEG = 21f
        // Texture filters on the deviation field: box blur radius (px, two
        // passes ≈ gaussian) and unsharp-mask amount (0 = off).
        private const val WALL_BLUR_PX = 1
        private const val WALL_SHARPEN = 2.57f
        private const val FLOOR_BLUR_PX = 2
        private const val FLOOR_SHARPEN = 1.49f
        private const val WALL_TILE_M = 1.26f
        private const val FLOOR_TILE_M = 1.15f
        private const val WALL_CONTRAST = 0.43f
        private const val FLOOR_CONTRAST = 2.52f
        private const val WALL_GREY = 45          // neutral base rgb(g-2, g, g+6)
        private const val WALL_ACCENT = 0.46f     // accent share of the wall tint
        private const val FLOOR_BASE = 15         // near-black base rgb(b, b, b+2)
        private const val FLOOR_ACCENT = 0.18f
        // Ceiling: same pattern as the walls, darker.
        private const val CEILING_DARKEN = 0.16f
        // Textured floor plane over the slab top: the slab is a CubeNode
        // (UVs fixed 0..1 per face, no tiling), so the visible floor is a
        // tiled PlaneNode lifted clear of the slab face (no z-fight) and
        // below the blob shadows (+0.015).
        private const val FLOOR_PLANE_LIFT = 0.005f

        // Studio front wall (z-, behind the listener): large tinted
        // control-room window plus a door on the listener's right (world
        // -X). Offsets are local to the wall plane (origin at the wall
        // centre, world Y = 0.5; +Z points into the room). Floor surface
        // sits at local -1.84.
        private const val WALL_LOCAL_FLOOR_Y = ROOM_FLOOR_SURFACE_Y - (ROOM_FLOOR_Y + ROOM_CEIL_Y) / 2f
        private const val GLASS_CENTER_X = 0.6f
        private const val GLASS_W = 4.4f
        private const val GLASS_H = 1.7f
        private const val GLASS_BOTTOM_ABOVE_FLOOR = 0.95f
        private const val GLASS_FRAME_T = 0.08f      // frame bar thickness
        private const val GLASS_FRAME_D = 0.10f      // frame depth into the room
        private const val DOOR_CENTER_X = -3.0f
        private const val DOOR_W = 1.0f
        private const val DOOR_H = 2.15f
        private const val DOOR_D = 0.05f
        private const val DOOR_FRAME_T = 0.07f
        private const val DOOR_FRAME_D = 0.09f
    }

    private var headNode: ModelNode? = null

    /** Custom point lights in the room corners. Replace the sceneview
     *  default sun (100k lux, sunny f/16) which either read as black
     *  under the ceiling (with shadows) or blew everything to white
     *  (without shadows). Four moderate-intensity POINT lights give a
     *  believable "closed cubicle with ceiling lamps" look and their
     *  intensity is what animates on the "lights off" toggle. */
    private val cornerLights = mutableListOf<LightNode>()
    private val cornerBaseIntensity = mutableListOf<Float>()

    // --- Cubicle nodes ---------------------------------------------------
    /** One plane per wall / ceiling / bottom. Each carries its own
     *  inward-facing normal so single-sided culling shows exactly the
     *  interior with correct lighting — no winding or scale tricks. */
    private val wallNodes = mutableListOf<PlaneNode>()
    // Individual surfaces for the dollhouse visibility logic — each hides
    // itself when the camera crosses its plane.
    private var ceilingNode: PlaneNode? = null
    private var wallPlusX: PlaneNode? = null
    private var wallMinusX: PlaneNode? = null
    private var wallPlusZ: PlaneNode? = null
    private var wallMinusZ: PlaneNode? = null
    private var floorNode: CubeNode? = null
    private var roomMaterial: MaterialInstance? = null
    private var floorMaterial: MaterialInstance? = null
    // Corner bass traps (children of the scene root). Each corner is
    // shared by two adjacent walls; the trap hides when either of them
    // does, so the trap never lingers in the air after the dollhouse
    // culls its side.
    private data class TrapEntry(
        val node: CylinderNode,
        val sx: Int, val sz: Int
    )
    private val trapEntries = mutableListOf<TrapEntry>()

    // Song card ("TV") on the back wall. Bezel is a matte cube, image is
    // an ImageNode whose bitmap is composited once per song. All children
    // of the back wall so the dollhouse toggle hides them with it.
    private var screenBezel: CubeNode? = null
    private var screenImage: ImageNode? = null
    private var screenBitmap: Bitmap? = null
    private var backlightNode: ImageNode? = null
    /** Accent the room surfaces, selection ring and omni halo are tinted with. */
    private var roomAccent: Int = 0

    // Surface textures: tileable deviation fields generated once, re-tinted
    // with the room accent into bitmaps uploaded to the material textures.
    // The SceneView textured material has no color factor or normal map, so
    // the tint is baked into the texture and the "rough" read comes from
    // albedo variation (cork granules, carpet loops).
    /** Deviation fields per pattern, generated on first use. */
    // Filled on the bake thread; the debug pattern picker reads it on main.
    private val patternFields = java.util.concurrent.ConcurrentHashMap<SurfacePattern, FloatArray>()

    // --- Off-main baking --------------------------------------------------
    // Procedural textures, the song card and the TV backlight are pure CPU
    // work on bitmaps: they are computed on a single background thread and
    // only the upload to Filament runs on the main thread. Opening the scene
    // or switching songs no longer blocks the UI on them. Until a bake lands
    // the surfaces show a flat placeholder of the same size (a Filament
    // texture keeps the size it was built with); the scene counts as warm
    // only once every pending bake is applied (see checkWarm).
    private val bakeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "SceneBake").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var released = false
    private var bakesInFlight = 0
    private var roomBakeGen = 0
    private var cardBakeGen = 0

    /** Runs [work] on the bake thread and [apply] with its result on the
     *  main thread (dropped once the controller is released). */
    private fun <T> bakeAsync(work: () -> T, apply: (T) -> Unit) {
        if (released) return
        bakesInFlight++
        try {
            bakeExecutor.execute {
                val result = runCatching(work)
                mainHandler.post {
                    bakesInFlight--
                    if (released) return@post
                    result.onSuccess(apply).onFailure { Log.w(TAG, "scene bake failed", it) }
                    // Two frames for the upload to reach the screen.
                    bakeVisibleAtFrame = framesRendered + 2
                    wake()
                    checkWarm()
                    checkFrameWaiters()
                }
            }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            bakesInFlight--
        }
    }
    private var wallTexture: com.google.android.filament.Texture? = null
    private var ceilingTexture: com.google.android.filament.Texture? = null
    private var floorTexture: com.google.android.filament.Texture? = null
    private var panelTexture: com.google.android.filament.Texture? = null
    private var trapTexture: com.google.android.filament.Texture? = null
    private var ceilingMaterial: MaterialInstance? = null

    /** Procedural surface patterns for walls/ceiling and floor. Each one
     *  carries the contrast and tile size (metres per texture repeat) that
     *  suit it on each surface; NaN = not offered for that surface. */
    enum class SurfacePattern(
        val label: String,
        val wallContrast: Float, val wallTileM: Float,
        val floorContrast: Float, val floorTileM: Float,
    ) {
        CORK("Corcho", 0.44f, 0.25f, Float.NaN, Float.NaN),
        PLASTER("Yeso", 0.6f, 2.0f, Float.NaN, Float.NaN),
        FOAM("Espuma acústica", 1.0f, 2.4f, Float.NaN, Float.NaN),
        WOOD_SLATS("Listones de madera", 0.8f, 0.96f, Float.NaN, Float.NaN),
        FELT("Fieltro", WALL_CONTRAST, WALL_TILE_M, 1.0f, 0.6f),
        CARPET_LOOP("Moqueta de bucle", Float.NaN, Float.NaN, FLOOR_CONTRAST, FLOOR_TILE_M),
        CARPET_PLUSH("Moqueta lisa", Float.NaN, Float.NaN, 1.2f, 1.0f),
        WOOD_PLANKS("Tarima", Float.NaN, Float.NaN, 1.0f, 1.2f),
        TERRAZZO("Terrazo", Float.NaN, Float.NaN, 1.0f, 1.0f);

        val onWall get() = !wallContrast.isNaN()
        val onFloor get() = !floorContrast.isNaN()
    }

    /** Live-tunable look parameters (debug panel in debuggable builds).
     *  Defaults are the shipped values; [describe] prints them in a form
     *  ready to hard-code. */
    class Tuning {
        var wallPattern = WALL_PATTERN
        var floorPattern = FLOOR_PATTERN
        var wallGrey = WALL_GREY
        var wallAccent = WALL_ACCENT
        var wallContrast = WALL_CONTRAST
        var wallTileM = WALL_TILE_M
        var ceilingDarken = CEILING_DARKEN
        var floorBase = FLOOR_BASE
        var floorAccent = FLOOR_ACCENT
        var floorContrast = FLOOR_CONTRAST
        var floorTileM = FLOOR_TILE_M
        var panelPattern = PANEL_PATTERN
        var trapPattern = TRAP_PATTERN
        var wallRotationDeg = WALL_ROTATION_DEG
        var floorRotationDeg = FLOOR_ROTATION_DEG
        var wallBlurPx = WALL_BLUR_PX
        var wallSharpen = WALL_SHARPEN
        var floorBlurPx = FLOOR_BLUR_PX
        var floorSharpen = FLOOR_SHARPEN
        var backlightSize = BACKLIGHT_SIZE
        var backlightAlpha = BACKLIGHT_PEAK_ALPHA
        var backlightFalloff = BACKLIGHT_FALLOFF
        var backlightBlur = BACKLIGHT_BLUR_PX
        var lampLumens = LAMP_INTENSITY
        var ambientFactor = AMBIENT_FACTOR
        var focalMm = CAMERA_FOCAL_MM.toFloat()

        fun describe(): String = String.format(java.util.Locale.US,
            "WALL_PATTERN=%s FLOOR_PATTERN=%s PANEL_PATTERN=%s TRAP_PATTERN=%s " +
            "WALL_ROTATION_DEG=%.0f FLOOR_ROTATION_DEG=%.0f WALL_BLUR_PX=%d WALL_SHARPEN=%.2f " +
            "FLOOR_BLUR_PX=%d FLOOR_SHARPEN=%.2f ", wallPattern.name, floorPattern.name,
            panelPattern.name, trapPattern.name, wallRotationDeg, floorRotationDeg,
            wallBlurPx, wallSharpen, floorBlurPx, floorSharpen) +
            String.format(java.util.Locale.US,
            "WALL_GREY=%d WALL_ACCENT=%.2f WALL_CONTRAST=%.2f WALL_TILE_M=%.2f " +
            "CEILING_DARKEN=%.2f FLOOR_BASE=%d FLOOR_ACCENT=%.2f FLOOR_CONTRAST=%.2f " +
            "FLOOR_TILE_M=%.2f BACKLIGHT_SIZE=%.2f BACKLIGHT_PEAK_ALPHA=%.2f " +
            "BACKLIGHT_FALLOFF=%.2f BACKLIGHT_BLUR_PX=%d LAMP_INTENSITY=%.0f " +
            "AMBIENT_FACTOR=%.2f CAMERA_FOCAL_MM=%.1f",
            wallGrey, wallAccent, wallContrast, wallTileM, ceilingDarken, floorBase,
            floorAccent, floorContrast, floorTileM, backlightSize, backlightAlpha,
            backlightFalloff, backlightBlur, lampLumens, ambientFactor, focalMm)
    }

    val tuning = Tuning()

    /** Wall tint: accent share over a dark neutral grey. */
    private fun wallTintFor(accent: Int): Int {
        val g = tuning.wallGrey
        val grey = Color.rgb((g - 2).coerceIn(0, 255), g, (g + 6).coerceIn(0, 255))
        return androidx.core.graphics.ColorUtils.blendARGB(accent, grey, 1f - tuning.wallAccent)
    }

    /** Ceiling tint: the wall tint darkened by the ceiling factor. */
    private fun ceilingTintFor(accent: Int): Int =
        androidx.core.graphics.ColorUtils.blendARGB(wallTintFor(accent), Color.BLACK, tuning.ceilingDarken)

    /** Floor tint: near-black base with an accent share. */
    private fun floorTintFor(accent: Int): Int {
        val b = tuning.floorBase
        val base = Color.rgb(b, b, (b + 2).coerceIn(0, 255))
        return androidx.core.graphics.ColorUtils.blendARGB(base, accent, tuning.floorAccent)
    }

    /** Re-tints walls, ceiling and floor to [accent] (texture re-bake,
     *  instant — a song change runs it under the black veil) and rebuilds
     *  the selection ring / omni halo bitmaps, swapping them into the live
     *  sprites. */
    fun setRoomAccent(accent: Int) {
        if (accent == roomAccent) return
        roomAccent = accent
        rebakeRoomTextures()
        // Ring + halo: new bitmaps; the previous ones are left to the GC
        // (texture uploads are asynchronous and may still read them).
        val ring = buildRingBitmap()
        val omni = buildOmniGlow()
        ringBitmap = ring
        omniBitmap = omni
        for (s in sprites.values) {
            runCatching {
                s.ring?.let { it.bitmap = ring; it.texture.generateMipmaps(sceneView.engine) }
                s.omni?.let { it.bitmap = omni; it.texture.generateMipmaps(sceneView.engine) }
            }
        }
    }

    /** Baked room surfaces, ready to upload. */
    private class RoomBitmaps(
        val wall: Bitmap, val ceiling: Bitmap, val floor: Bitmap,
        val panel: Bitmap, val trap: Bitmap
    )

    /** Pure bitmap work (bake thread): every room surface for [accent]. */
    private fun bakeRoomBitmaps(accent: Int): RoomBitmaps {
        val wf = surfaceField(tuning.wallPattern, floor = false)
        return RoomBitmaps(
            wall = bakeSurface(wf, wallTintFor(accent), tuning.wallContrast),
            ceiling = bakeSurface(wf, ceilingTintFor(accent), tuning.wallContrast),
            floor = bakeSurface(surfaceField(tuning.floorPattern, floor = true),
                floorTintFor(accent), tuning.floorContrast),
            // Panels and traps keep their own colors (no accent).
            panel = bakePanelSurface(),
            trap = bakeTrapSurface()
        )
    }

    /** Re-bakes wall, ceiling, floor, panel and trap textures from the
     *  cached fields with the current accent and tuning, off the main
     *  thread; the latest request wins. */
    private fun rebakeRoomTextures() {
        val gen = ++roomBakeGen
        val accent = roomAccent
        bakeAsync({ bakeRoomBitmaps(accent) }) { b ->
            if (gen != roomBakeGen) return@bakeAsync
            val engine = sceneView.engine
            runCatching {
                for ((tex, bmp) in listOf(wallTexture to b.wall, ceilingTexture to b.ceiling,
                        floorTexture to b.floor, panelTexture to b.panel, trapTexture to b.trap)) {
                    if (tex == null) continue
                    tex.setBitmap(engine, bmp)
                    tex.generateMipmaps(engine)
                }
            }.onFailure { Log.w(TAG, "room re-tint failed", it) }
        }
    }

    /** Flat [tint] surface the size of a baked one: placeholder texture
     *  until the bake lands (a texture keeps its build size). */
    private fun flatSurface(tint: Int, repeatY: Int = 1): Bitmap =
        Bitmap.createBitmap(SURFACE_TEX_SIZE, SURFACE_TEX_SIZE * repeatY, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(tint) }

    /** Surfaces with a selectable pattern. Panels and traps use the wall
     *  catalogue; the floor its own. */
    enum class Surface { WALL, FLOOR, PANEL, TRAP }

    /** One texture tile of [pattern] as it would look on [surface], with
     *  the surface's current tint hue and the pattern's own contrast. The
     *  tint is exposure-lifted (the lamps do that in the scene) so dark
     *  bases still show the pattern. Debug pattern picker thumbnails. */
    fun patternPreview(pattern: SurfacePattern, surface: Surface): Bitmap {
        val accent = roomAccent
        val floor = surface == Surface.FLOOR
        val tint = when (surface) {
            Surface.WALL -> wallTintFor(accent)
            Surface.FLOOR -> floorTintFor(accent)
            Surface.PANEL -> PANEL_COLOR
            Surface.TRAP -> TRAP_COLOR
        }
        val lum = ((Color.red(tint) + Color.green(tint) + Color.blue(tint)) / 3).coerceAtLeast(1)
        val k = (110f / lum).coerceIn(1f, 10f)
        val lifted = Color.rgb(
            (Color.red(tint) * k).toInt().coerceAtMost(255),
            (Color.green(tint) * k).toInt().coerceAtMost(255),
            (Color.blue(tint) * k).toInt().coerceAtMost(255))
        return bakeSurface(surfaceField(pattern, floor), lifted,
            if (floor) pattern.floorContrast else pattern.wallContrast)
    }

    /** Swaps the surface patterns live. A wall or floor whose pattern
     *  changes takes that pattern's contrast and tile size. */
    fun setSurfacePatterns(wall: SurfacePattern, floor: SurfacePattern,
                           panel: SurfacePattern, trap: SurfacePattern) {
        if (panel.onWall) tuning.panelPattern = panel
        if (trap.onWall) tuning.trapPattern = trap
        if (wall.onWall && wall != tuning.wallPattern) {
            tuning.wallPattern = wall
            tuning.wallContrast = wall.wallContrast
            tuning.wallTileM = wall.wallTileM
        }
        if (floor.onFloor && floor != tuning.floorPattern) {
            tuning.floorPattern = floor
            tuning.floorContrast = floor.floorContrast
            tuning.floorTileM = floor.floorTileM
        }
        applyTuning()
    }

    /** Planes whose UV tiling follows the tuning: (node, span u, span v, isFloor). */
    private data class TiledPlane(val node: PlaneNode, val spanU: Float, val spanV: Float, val floor: Boolean)
    private val tiledPlanes = mutableListOf<TiledPlane>()

    /** Tiling + rotation of one room plane. SceneView's Plane only scales
     *  UVs, so the scaled vertices are rebuilt with their UVs rotated about
     *  the origin (texture sampler repeats, any angle stays seamless) and
     *  written in place into the plane's vertex buffer. */
    private fun applyPlaneUv(tp: TiledPlane) {
        val tile = if (tp.floor) tuning.floorTileM else tuning.wallTileM
        val deg = if (tp.floor) tuning.floorRotationDeg else tuning.wallRotationDeg
        runCatching {
            val scale = io.github.sceneview.geometries.UvScale(tp.spanU / tile, tp.spanV / tile)
            tp.node.updateGeometry(uvScale = scale)
            if (deg == 0f) return@runCatching
            val g = tp.node.geometry
            val a = Math.toRadians(deg.toDouble())
            val c = kotlin.math.cos(a).toFloat()
            val s = kotlin.math.sin(a).toFloat()
            val verts = io.github.sceneview.geometries.Plane.getVertices(g.size, g.center, g.normal, scale)
                .map { v ->
                    val uv = v.uvCoordinate ?: return@map v
                    v.copy(uvCoordinate = io.github.sceneview.geometries.UvCoordinate(
                        uv.x * c - uv.y * s, uv.x * s + uv.y * c))
                }
            g.update(sceneView.engine, vertices = verts)
        }
    }
    /** Artwork the backlight was last built from (re-used by tuning). */
    private var backlightCover: Bitmap? = null

    /** Applies the whole [tuning] live: textures, UV tiling, backlight and
     *  lamps. Debug panel only; cheap enough to run on every slider tick. */
    fun applyTuning() {
        rebakeRoomTextures()
        for (tp in tiledPlanes) applyPlaneUv(tp)
        backlightNode?.let { glow ->
            runCatching {
                glow.bitmap = buildBacklightBitmap(backlightCover)
                glow.texture.generateMipmaps(sceneView.engine)
                val s = tuning.backlightSize / BACKLIGHT_SIZE
                glow.scale = Scale(s, s, 1f)
            }
        }
        for (i in cornerBaseIntensity.indices) cornerBaseIntensity[i] = tuning.lampLumens
        setLightIntensity(currentLightIntensity)
        runCatching { sceneView.cameraNode.focalLength = tuning.focalMm.toDouble() }
    }

    /** Reflection streaks over the control-room window. */
    private var glassStreakBitmap: Bitmap? = null
    private var backWallRef: PlaneNode? = null
    private var pendingCover: java.io.File? = null
    private var pendingTitle: String = ""
    private var pendingArtist: String = ""

    // --- Lighting --------------------------------------------------------
    private var baseIndirectIntensity: Float = Float.NaN
    private var currentLightIntensity: Float = LIGHT_INTENSITY_ON

    /** One spherical stem sprite: node + spherical coordinates. */
    private data class StemSprite(
        val id: String,
        val node: ImageNode,
        val shadow: ImageNode?,
        val ring: ImageNode?,
        val omni: ImageNode?,
        val mutedNode: ImageNode,
        var azimuthRad: Float,
        // Linear height offset (metres) from the head centre. The pose is
        // cylindrical: distance is the horizontal radius, height is free.
        var heightM: Float,
        var distance: Float
    )

    /** Input record for {@link #setStems}: id + icon bitmap + cylindrical pose. */
    data class StemPose(
        @JvmField val id: String,
        @JvmField val bitmap: Bitmap,
        @JvmField val azimuthRad: Float,
        @JvmField val heightM: Float,
        @JvmField val distance: Float
    )

    private val sprites = mutableMapOf<String, StemSprite>()
    private var frameListenerAttached = false
    private var lastCamX = Float.NaN
    private var lastCamY = Float.NaN
    private var lastCamZ = Float.NaN
    private val lastEngineUpdateNs = mutableMapOf<String, Long>()

    // Sprite ground shadows come in three tiers (near / mid / far). The
    // tier is picked from the sprite height above the floor: close stems
    // cast a tight, dark disc; elevated stems cast a wider, fainter and
    // more diffuse one — the inverse-square read of a ceiling light.
    private var shadowBitmaps: List<Bitmap> = emptyList()
    private var headShadowBitmap: Bitmap? = null
    private var headShadowNode: ImageNode? = null
    private var ringBitmap: Bitmap? = null
    private var omniBitmap: Bitmap? = null
    // Per-sprite dimmed icons (muted state) — recycled in clearSprites.
    private val dimmedBitmaps = mutableListOf<Bitmap>()

    // --- Dance state ---
    private var danceActive: Boolean = false
    private var danceStemIds: Set<String> = emptySet()
    private var danceSineIds: Set<String> = emptySet()
    // Independent oscillation phase per stem (radians). Advances at the
    // per-stem speed — decoupled from the orbit speed so the up/down wave
    // can be tuned separately.
    private val danceOscPhase = mutableMapOf<String, Float>()
    // Per-stem wave speed (cycles/second), set by the bottom-row slider on
    // the currently selected stem.
    private val danceOscSpeeds = mutableMapOf<String, Float>()
    private var danceSpeed: Float = 0.15f
    private var danceDistance: Float = 1.5f
    private val danceSpeedFactor = mutableMapOf<String, Float>()
    private var danceLastNs: Long = 0L

    /** Pose change callback. The pose is cylindrical: [distance] is the
     *  horizontal radius (metres) and [elevationRad] the derived angle
     *  atan2(height, radius) — hosts convert to spherical for the engine. */
    fun interface DragListener {
        fun onStemPoseChanged(
            id: String,
            azimuthRad: Float,
            elevationRad: Float,
            distance: Float,
            isFinal: Boolean
        )
    }
    private var dragListener: DragListener? = null

    enum class SpriteTap { TAP, DOUBLE_TAP, LONG_PRESS }
    fun interface TapListener {
        fun onSpriteTap(id: String, event: SpriteTap)
    }
    private var tapListener: TapListener? = null

    private var gestureDetector: GestureDetector? = null
    private var gestureSprite: StemSprite? = null

    // In-flight drag state. A drag arms immediately when the finger goes
    // down on the already-selected sprite; other sprites require a tap
    // first (they become selected, next touch drags). This keeps orbit
    // camera and stem drag on the same surface without a wait timer.
    private var draggingSprite: StemSprite? = null
    private var readyToDrag: Boolean = false
    // Raw touch bookkeeping for the camera clamp's grab re-arm — the
    // room clamp rebuilds the manipulator, which discards the orbit grab
    // the gesture detector armed on the previous instance; tracking the
    // finger here lets the resync re-arm an equivalent grab so an orbit
    // drag pinned against a boundary keeps responding.
    private var touchCount: Int = 0
    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0
    private var dragOffsetX: Float = 0f    // screen px between finger and sprite center at ACTION_DOWN
    private var dragOffsetY: Float = 0f
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragMoved: Boolean = false
    // Long-press bookkeeping. The GD long-press cannot be used here: the
    // sprite drag arms on the FIRST move event (by design), so any finger
    // jitter sets dragMoved before the 500ms GD timer fires and the press
    // is suppressed forever. A dedicated runnable cancelled only by real
    // movement (> touch slop) keeps hold-to-mute alive.
    private var longPressRunnable: Runnable? = null
    private var longPressFired  = false
    private var touchSlopPx: Int = 24
    // Own double-tap bookkeeping (detected in the touch hook on UP): the
    // GD tolerance (~touch slop) is too strict for on-screen sprites and
    // its onDoubleTap was gated by dragMoved, which finger jitter set.
    private var lastTapUpTime: Long = 0L
    private var lastTapUpX: Float = 0f
    private var lastTapUpY: Float = 0f
    private var doubleTapRadiusPx: Int = 180

    private var azimuthDeg: Float = DEF_AZIMUTH_DEG
    private var pitchDeg:   Float = DEF_PITCH_DEG
    private var distance:   Float = DEF_DISTANCE

    /** Id of the currently selected sprite, if any. Drives the selection
     *  ring visibility from the frame callback. */
    private var selectedId: String? = null

    /** Frames rendered since setup; drives [runWhenWarm]. */
    private var framesRendered = 0
    private var setupAtMs = 0L
    private val warmWaiters = mutableListOf<Runnable>()

    /** Runs [action] once the scene is visually settled: a few frames
     *  rendered (surface + first uploads done) and past the cold-start IBL
     *  re-apply, so the reveal never shows a brightness jump. Immediate when
     *  already warm. */
    fun runWhenWarm(action: Runnable) {
        wake()
        warmWaiters.add(action)
        checkWarm()
    }

    fun cancelWarmWaiters() {
        warmWaiters.clear()
        frameWaiters.clear()
    }

    /** (target frame count, action) pairs for [runAfterFrames]. */
    private val frameWaiters = mutableListOf<Pair<Int, Runnable>>()

    /** Runs [action] once [frames] more frames have been rendered — lets
     *  freshly swapped textures and geometry reach the screen before a
     *  cover is lifted. Only fires while the frame loop runs (resumed). */
    fun runAfterFrames(frames: Int, action: Runnable) {
        wake()
        frameWaiters.add(Pair(framesRendered + frames, action))
    }

    /** Frame count by which the last applied bake is on screen. */
    private var bakeVisibleAtFrame = 0

    private fun checkFrameWaiters() {
        if (frameWaiters.isEmpty()) return
        // Covers stay up while a bake is pending or not yet drawn.
        if (bakesInFlight > 0 || framesRendered < bakeVisibleAtFrame) return
        val due = frameWaiters.filter { framesRendered >= it.first }
        if (due.isEmpty()) return
        frameWaiters.removeAll(due)
        due.forEach { it.second.run() }
    }

    private fun checkWarm() {
        if (warmWaiters.isEmpty()) return
        if (framesRendered < WARM_MIN_FRAMES) return
        if (bakesInFlight > 0 || framesRendered < bakeVisibleAtFrame) return
        if (SystemClock.uptimeMillis() - setupAtMs < WARM_MIN_MS) return
        val pending = warmWaiters.toList()
        warmWaiters.clear()
        pending.forEach { it.run() }
    }

    /** Builds the whole scene at once (callers already behind a cover). */
    fun setup() {
        setupSteps().forEach { it.run() }
    }

    /** The scene construction split into short main-thread steps, in
     *  order. The host can run them one per frame so the UI and the
     *  playback controls stay responsive while the scene builds. Every step
     *  is required and must run in this order. */
    fun setupSteps(): List<Runnable> = listOf(
        Runnable { beginSetup() },
        Runnable { addRoomShell() },
        Runnable { addRoomPanels(0) },
        Runnable { addRoomPanels(1) },
        Runnable { addRoomPanels(2) },
        Runnable { addRoomScreen() },
        Runnable { addRoomFrontWall() },
        Runnable { addRoomTraps() },
        Runnable { loadHead() },
        Runnable { finishSetup() }
    )

    private fun beginSetup() {
        setupAtMs = SystemClock.uptimeMillis()
        // Stored by the node and re-applied on every viewport resize.
        runCatching { sceneView.cameraNode.focalLength = CAMERA_FOCAL_MM }
            .onFailure { Log.w(TAG, "focal length not applied", it) }
        roomAccent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(activity)
        touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop
        doubleTapRadiusPx = (DOUBLE_TAP_RADIUS_DP * activity.resources.displayMetrics.density).toInt()
        prepareBitmaps()
        installTouchListener()
    }

    private fun finishSetup() {
        // Apply any song-card values that arrived before setup(): the
        // host may push metadata as soon as the controller exists.
        if (pendingTitle.isNotEmpty() || pendingArtist.isNotEmpty() || pendingCover != null) {
            setSongCard(pendingCover, pendingTitle, pendingArtist)
        }
        // Applying the camera pose runs after the head is in the scene so
        // the first frame renders with the head visible instead of the
        // SceneView default which may point at empty space.
        sceneView.post {
            applyCameraPose()
            configureSceneLighting()
            snapshotBaseLightIntensities()
        }
        // Re-apply once the render pipeline is warm: on a cold start the
        // IBL baseline can still be degenerate at post time, and a single
        // retry after the first frames guarantees the environment light
        // reaches its intended level (matches the brightness of a warm
        // re-entry into the player).
        sceneView.postDelayed({ setLightIntensity(currentLightIntensity) }, 500)
        sceneView.postDelayed({ setLightIntensity(currentLightIntensity) }, 1500)
    }

    /** Replaces the sceneview default sun with 4 point lights inside the
     *  cubicle. The default main light is a 100k-lux directional (sunny
     *  f/16); either it cast shadows (ceiling occluded the head, room
     *  read as black) or it did not (the whole scene blew out to white).
     *  A closed room has to be lit from inside — hence four moderate
     *  ceiling lamps near the corners plus the sceneview default IBL for
     *  soft ambient fill. The default sun is disabled (intensity = 0)
     *  instead of removed so its bookkeeping stays consistent. Camera
     *  exposure stays at the sunny-16 default so raw values behave
     *  predictably — brightness is tuned via the lamp intensities. */
    private fun configureSceneLighting() {
        try {
            val main = sceneView.mainLightNode
            if (main != null) {
                main.isShadowCaster = false
                main.intensity = 0f
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mainLight tweak failed", t)
        }
        try {
            // Keep the lamps well clear of the ceiling: a point light this
            // close paints a visible hotspot disc on the plane above it.
            val lampY = ROOM_CEIL_Y - 1.0f
            val kx = ROOM_HALF_X * 0.72f
            val kz = ROOM_HALF_Z * 0.72f
            val corners = listOf(
                Position(-kx, lampY, -kz),
                Position( kx, lampY, -kz),
                Position(-kx, lampY,  kz),
                Position( kx, lampY,  kz)
            )
            for (pos in corners) {
                addCornerLight(pos, tuning.lampLumens)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "corner lights setup failed", t)
        }
    }

    private fun addCornerLight(pos: Position, intensityLm: Float) {
        try {
            val warmWhite = Colors.cct(3500f).toColor()
            val light = LightNode(
                engine = sceneView.engine,
                type = LightManager.Type.POINT,
                apply = {
                    color(warmWhite.r, warmWhite.g, warmWhite.b)
                    intensity(intensityLm)
                    position(pos.x, pos.y, pos.z)
                    falloff(ROOM_HALF_X * 3f)
                    castShadows(false)
                }
            )
            sceneView.addChildNode(light)
            cornerLights.add(light)
            cornerBaseIntensity.add(intensityLm)
        } catch (t: Throwable) {
            Log.w(TAG, "corner light at $pos failed", t)
        }
    }

    fun setDragListener(l: DragListener?) { dragListener = l }
    fun setTapListener(l: TapListener?)   { tapListener  = l }

    /** Visually mark a sprite as muted (dimmed) so the flat-mode mute3D
     *  state carries over into the scene. */
    fun setStemMuted(id: String, muted: Boolean) {
        val s = sprites[id] ?: return
        try {
            // Mute reads as a darkened icon (flat-mode parity): swap the
            // visible sprite for its pre-rendered dimmed twin. No scale
            // change, so a concurrent drag bump never fights the state.
            s.node.isVisible = !muted
            s.mutedNode.isVisible = muted
        } catch (_: Throwable) {}
    }

    // --- Dance ------------------------------------------------------------

    /** [randomSwing]: each swinging stem starts at a random point of its
     *  wave instead of the midline, so the stems bob out of sync. */
    fun startDance(stemIds: Collection<String>, sineIds: Set<String>, speed: Float, distance: Float,
                   randomSwing: Boolean) {
        danceStemIds = stemIds.toSet()
        danceSineIds = sineIds.toSet()
        danceSpeed = speed
        danceDistance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        danceSpeedFactor.clear()
        danceOscPhase.clear()
        val rnd = java.util.Random()
        for (id in danceStemIds) {
            danceSpeedFactor[id] = 0.7f + rnd.nextFloat() * 0.6f
            sprites[id]?.let { s ->
                s.distance = danceDistance
                // Phase zero → sin(0)=0, the wave begins from the
                // chest-height midline; random → anywhere on the cycle.
                danceOscPhase[id] =
                    if (randomSwing) rnd.nextFloat() * 2f * Math.PI.toFloat() else 0f
                applySpritePose(s)
            }
        }
        danceLastNs = System.nanoTime()
        danceActive = true
        (sceneView as? FisionSceneView)?.continuousActive = true
        ensureBillboardFrameListener()
    }

    fun stopDance() {
        danceActive = false
        (sceneView as? FisionSceneView)?.continuousActive = false
        wake()
        danceStemIds = emptySet()
        danceSineIds = emptySet()
        danceOscPhase.clear()
        for (s in sprites.values) {
            dragListener?.onStemPoseChanged(
                s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ true
            )
        }
    }

    fun isDanceActive(): Boolean = danceActive

    fun setDanceSpeed(speed: Float) { danceSpeed = speed }
    fun setStemOscSpeed(id: String, cyclesPerSec: Float) {
        danceOscSpeeds[id] = cyclesPerSec.coerceIn(0.05f, 3f)
    }
    fun getStemOscSpeed(id: String): Float =
        danceOscSpeeds[id] ?: DEFAULT_OSC_SPEED
    fun setDanceDistance(distance: Float) {
        danceDistance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        if (danceActive) {
            for (id in danceStemIds) {
                sprites[id]?.let { s ->
                    s.distance = danceDistance
                    applySpritePose(s)
                }
            }
        }
    }

    fun setStemSelected(id: String?) {
        selectedId = id
        for ((sid, spr) in sprites) {
            val isSel = (sid == id)
            spr.ring?.let { it.isVisible = isSel }
        }
        if (id != null) {
            try {
                sceneView.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CONTEXT_CLICK
                )
            } catch (_: Throwable) {}
        }
    }

    // --- Cubicle ---------------------------------------------------------

    /** Walls (front z-, back z+, right x+, left x-) and ceiling, in that order. */
    private var roomPlanes: List<PlaneNode> = emptyList()
    private var panelMaterialInstance: MaterialInstance? = null

    /** Room stage 1: textures, materials, walls, ceiling and floor.
     *  The cubicle walls as six independent inward-facing planes (4 walls
     *  + ceiling + bottom under the floor slab). SceneView's Plane only
     *  generates two orientations — XY planes whose front face points +Z
     *  and XZ planes whose front face points +Y (any other axis layout
     *  degenerates) — so each wall is built in one of those two shapes and
     *  the node is rotated onto its final position. Proper rotations keep
     *  winding and the baked normal attribute consistent, so single-sided
     *  culling shows the interior and N·L stays positive against the
     *  corner lamps. A custom inward-wound cube was tried first but
     *  rendered glitched (per-face shading splits); exact shared corner
     *  coordinates keep the seams closed. The planes share one material
     *  instance. */
    private fun addRoomShell() {
        try {
            val width  = 2f * ROOM_HALF_X
            val depth  = 2f * ROOM_HALF_Z
            val height = ROOM_CEIL_Y - ROOM_FLOOR_Y
            val centerY = (ROOM_FLOOR_Y + ROOM_CEIL_Y) / 2f

            // Walls + ceiling and floor (patterns from the tuning) carry the player
            // accent (picked accent, or the song's when the player adopts
            // artwork colors) over dark neutral bases; see *TintFor. The
            // ceiling reuses the wall field with a darker tint.
            // Textures start flat in their tint; the patterned bake follows
            // from the bake thread (rebakeRoomTextures at the end).
            val accent = roomAccent
            val engine = sceneView.engine
            fun texture(bmp: Bitmap) =
                io.github.sceneview.texture.ImageTexture.Builder().bitmap(bmp).build(engine)
            val wallTex = texture(flatSurface(wallTintFor(accent))).also { wallTexture = it }
            val ceilTex = texture(flatSurface(ceilingTintFor(accent))).also { ceilingTexture = it }
            val floorTex = texture(flatSurface(floorTintFor(accent))).also { floorTexture = it }
            roomMaterial = sceneView.materialLoader.createTextureInstance(
                wallTex, true, 0f, 0.9f, 0.02f
            )
            ceilingMaterial = sceneView.materialLoader.createTextureInstance(
                ceilTex, true, 0f, 0.9f, 0.02f
            )
            // Floor: same lit path as the walls so the four ceiling lamps
            // hit both surfaces consistently — the earlier emissive shortcut
            // auto-lit the floor and broke the hierarchy (floor read brighter
            // than the walls). Keep roughness high: low values produced a
            // piano-black mirror.
            floorMaterial = sceneView.materialLoader.createTextureInstance(
                floorTex, true, 0f, 0.95f, 0.02f
            )

            // (size, anchor, rotation, local normal, material). XY planes
            // face +Z locally; XZ planes face +Y. Node rotations are in
            // DEGREES (SceneView converts them to radians internally) and
            // single-axis, so the euler order is unambiguous. Each plane is
            // built centered on its own origin and parented under an anchor
            // Node at the wall center — a node rotates about its origin,
            // so baking the offset into the vertices would swing the wall
            // around the room center instead of its middle. Corners sit
            // exactly on the room bounds so adjacent planes share edges
            // with no gap and no overlap.
            val walls = listOf(
                // Front wall: XY plane at z=-6, front face +Z already inward.
                PlaneSpec(Size(width, height, 0f), Position(0f, centerY, -ROOM_HALF_Z),
                          Rotation(0f, 0f, 0f), Direction(0f, 0f, 1f), roomMaterial),
                // Back wall: XY plane at z=+6, yaw 180 turns +Z into -Z.
                PlaneSpec(Size(width, height, 0f), Position(0f, centerY,  ROOM_HALF_Z),
                          Rotation(0f, 180f, 0f), Direction(0f, 0f, 1f), roomMaterial),
                // Right wall: XY plane at x=+6, yaw -90 turns +Z into -X.
                PlaneSpec(Size(width, height, 0f), Position( ROOM_HALF_X, centerY, 0f),
                          Rotation(0f, -90f, 0f), Direction(0f, 0f, 1f), roomMaterial),
                // Left wall: XY plane at x=-6, yaw +90 turns +Z into +X.
                PlaneSpec(Size(width, height, 0f), Position(-ROOM_HALF_X, centerY, 0f),
                          Rotation(0f, 90f, 0f), Direction(0f, 0f, 1f), roomMaterial),
                // Ceiling: XZ plane at y=+ceil, roll 180 turns +Y into -Y.
                PlaneSpec(Size(width, 0f, width), Position(0f, ROOM_CEIL_Y, 0f),
                          Rotation(180f, 0f, 0f), Direction(0f, 1f, 0f), ceilingMaterial)
            )
            val created = mutableListOf<PlaneNode>()
            walls.forEach { spec ->
                val anchor = Node(sceneView.engine).apply { position = spec.center }
                // XY planes span (x, y), the XZ ceiling spans (x, z).
                val spanV = if (spec.size.y > 0f) spec.size.y else spec.size.z
                val wall = PlaneNode(
                    engine = sceneView.engine,
                    size = spec.size,
                    center = Position(0f, 0f, 0f),
                    normal = spec.normal,
                    uvScale = io.github.sceneview.geometries.UvScale(
                        spec.size.x / tuning.wallTileM, spanV / tuning.wallTileM),
                    materialInstance = spec.material
                ).apply { rotation = spec.rotation }
                tiledPlanes.add(TiledPlane(wall, spec.size.x, spanV, floor = false))
                anchor.addChildNode(wall)
                sceneView.addChildNode(anchor)
                wallNodes.add(wall)
                created.add(wall)
            }
            // List order: front (z-), back (z+), right (x+), left (x-), ceiling.
            wallMinusZ = created.getOrNull(0)
            wallPlusZ  = created.getOrNull(1)
            wallPlusX  = created.getOrNull(2)
            wallMinusX = created.getOrNull(3)
            ceilingNode = created.getOrNull(4)

            // Bottom plane under the slab, in the floor tone. Closes the
            // box so no black sliver shows between the slab edge and the
            // walls at grazing angles.
            val bottom = PlaneNode(
                engine = sceneView.engine,
                size = Size(width, 0f, width),
                center = Position(0f, ROOM_FLOOR_Y, 0f),
                normal = Direction(0f, 1f, 0f),
                materialInstance = floorMaterial
            )
            sceneView.addChildNode(bottom)
            wallNodes.add(bottom)

            // Floor: a thin dark slab sitting at the bottom of the room.
            // Black base + emissive tint, immune to lamp intensity.
            val floor = CubeNode(
                engine = sceneView.engine,
                size = Size(width - 0.05f, 0.06f, depth - 0.05f),
                center = Position(0f, 0f, 0f),
                materialInstance = floorMaterial
            ).apply {
                position = Position(0f, ROOM_FLOOR_Y + 0.03f, 0f)
            }
            sceneView.addChildNode(floor)
            floorNode = floor

            // Visible floor surface: tiled textured plane just above the slab
            // top (the slab's cube UVs stretch one tile over the whole room).
            val floorSurface = PlaneNode(
                engine = sceneView.engine,
                size = Size(width - 0.05f, 0f, depth - 0.05f),
                center = Position(0f, ROOM_FLOOR_SURFACE_Y + FLOOR_PLANE_LIFT, 0f),
                normal = Direction(0f, 1f, 0f),
                uvScale = io.github.sceneview.geometries.UvScale(
                    width / tuning.floorTileM, depth / tuning.floorTileM),
                materialInstance = floorMaterial
            )
            tiledPlanes.add(TiledPlane(floorSurface, width, depth, floor = true))
            sceneView.addChildNode(floorSurface)
            wallNodes.add(floorSurface)
            for (tp in tiledPlanes) applyPlaneUv(tp)
            roomPlanes = created
        } catch (t: Throwable) {
            Log.w(TAG, "Room not added", t)
        }
    }

    /** Room stage 2 (one call per wall, [index] 0..2 = back, right, left). */
    private fun addRoomPanels(index: Int) {
        if (roomPlanes.size < 5) return
        try {
            val engine = sceneView.engine
            // Studio furniture. Panels read as studio foam: matte, darker
            // than the walls, no accent so they never compete with the
            // sprites. Each panel is a child of its wall plane — the wall's
            // local +Z points into the room, so the same local offsets work
            // for every wall, and the dollhouse visibility toggle (which
            // flips the wall node) hides the panels along with it.
            val panelMaterial = panelMaterialInstance ?: run {
                val panelTex = io.github.sceneview.texture.ImageTexture.Builder()
                    .bitmap(flatSurface(PANEL_COLOR)).build(engine).also { panelTexture = it }
                sceneView.materialLoader.createTextureInstance(
                    panelTex, true, 0f, 0.95f, 0.02f
                ).also { panelMaterialInstance = it }
            }
            // Side and back walls only: the front wall is hidden by the
            // dollhouse for most of the session, so its panels would be
            // wasted geometry.
            val backWall = roomPlanes[1]
            backWallRef = backWall
            val panelWalls = listOf(backWall, roomPlanes[2], roomPlanes[3])
            val startX = -(PANEL_COLS - 1) * PANEL_COL_SPACING_X * 0.5f
            val startY = -(PANEL_ROWS - 1) * PANEL_ROW_SPACING_Y * 0.5f
            run {
                val w = panelWalls[index]
                val isBack = (w === backWall)
                for (col in 0 until PANEL_COLS) {
                    for (row in 0 until PANEL_ROWS) {
                        // Back wall carries the song-card screen: skip every
                        // panel that would overlap the bezel (plus a 0.1 m
                        // gap), so the hole follows the grid and screen size.
                        val px = startX + col * PANEL_COL_SPACING_X
                        val py = startY + row * PANEL_ROW_SPACING_Y
                        if (isBack
                            && abs(px) < (SCREEN_BEZEL_SIZE + PANEL_W) / 2f + 0.1f
                            && abs(py - SCREEN_LOCAL_Y) < (SCREEN_BEZEL_SIZE + PANEL_H) / 2f + 0.1f
                        ) continue
                        val panel = CubeNode(
                            engine = sceneView.engine,
                            size = Size(PANEL_W, PANEL_H, PANEL_D),
                            center = Position(0f, 0f, 0f),
                            materialInstance = panelMaterial
                        ).apply {
                            position = Position(px, py, PANEL_OFFSET_Z)
                        }
                        w.addChildNode(panel)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Room panels not added", t)
        }
    }

    /** Room stage 3: song-card screen, bezel and TV backlight. */
    private fun addRoomScreen() {
        val backWall = roomPlanes.getOrNull(1) ?: return
        try {
            // Song-card screen: bezel + square image plane. Parented under
            // the back wall so the dollhouse toggle hides them together.
            // Bezel sits at the same protrusion as the acoustic panels;
            // the image plane is nudged slightly further into the room so
            // it never z-fights the bezel face.
            val bezelMaterial = sceneView.materialLoader.createColorInstance(
                Color.rgb(10, 10, 12), 0f, 0.75f, 0.05f
            )
            val bezel = CubeNode(
                engine = sceneView.engine,
                size = Size(SCREEN_BEZEL_SIZE, SCREEN_BEZEL_SIZE, SCREEN_BEZEL_D),
                center = Position(0f, 0f, 0f),
                materialInstance = bezelMaterial
            ).apply {
                position = Position(0f, SCREEN_LOCAL_Y, SCREEN_BEZEL_D / 2f + 0.005f)
            }
            backWall.addChildNode(bezel)
            screenBezel = bezel

            // Ambient backlight behind the TV (unlit → reads as emitted
            // light on the dark wall). Recolored with each song card.
            try {
                val glow = ImageNode(
                    materialLoader = sceneView.materialLoader,
                    // Transparent until the first card bake lands.
                    bitmap = Bitmap.createBitmap(BACKLIGHT_BITMAP, BACKLIGHT_BITMAP,
                        Bitmap.Config.ARGB_8888),
                    size = Size(BACKLIGHT_SIZE, BACKLIGHT_SIZE, 0f)
                ).apply {
                    position = Position(0f, SCREEN_LOCAL_Y, BACKLIGHT_Z)
                }
                backWall.addChildNode(glow)
                backlightNode = glow
            } catch (t: Throwable) {
                Log.e(TAG, "TV backlight failed", t)
            }

            val placeholder = buildPlaceholderCardBitmap()
            screenBitmap = placeholder
            // Pass size explicitly (metres). If omitted the plane takes
            // normalize(Size(bitmap.w, bitmap.h)) which is a sub-1m vector.
            val image = ImageNode(
                materialLoader = sceneView.materialLoader,
                bitmap = placeholder,
                size = Size(SCREEN_IMG_SIZE, SCREEN_IMG_SIZE, 0f)
            ).apply {
                position = Position(0f, SCREEN_LOCAL_Y, SCREEN_BEZEL_D + 0.02f)
            }
            backWall.addChildNode(image)
            screenImage = image
        } catch (t: Throwable) {
            Log.w(TAG, "Room screen not added", t)
        }
    }

    /** Room stage 4: front wall (behind the listener), control-room window + door. */
    private fun addRoomFrontWall() {
        val front = roomPlanes.getOrNull(0) ?: return
        try {
            addStudioFrontWall(front)
        } catch (t: Throwable) {
            Log.e(TAG, "studio front wall failed", t)
        }
    }

    /** Room stage 5: corner bass traps, then the patterned texture bake. */
    private fun addRoomTraps() {
        if (roomPlanes.isEmpty()) return
        try {
            val engine = sceneView.engine
            // Bass traps: one vertical cylinder per corner, floor to
            // ceiling. Inset from the walls by their radius so they sit in
            // the corner without z-fighting either face. The (sx, sz)
            // signs identify the corner so updateRoomVisibility can hide
            // the trap when either adjacent wall goes.
            val trapTex = io.github.sceneview.texture.ImageTexture.Builder()
                .bitmap(flatSurface(TRAP_COLOR, trapRepeats())).build(engine).also { trapTexture = it }
            val trapMaterial = sceneView.materialLoader.createTextureInstance(
                trapTex, true, 0f, 0.95f, 0.02f
            )
            val trapHeight = ROOM_CEIL_Y - ROOM_FLOOR_Y
            val trapY = (ROOM_FLOOR_Y + ROOM_CEIL_Y) / 2f
            val trapInsetX = ROOM_HALF_X - TRAP_RADIUS
            val trapInsetZ = ROOM_HALF_Z - TRAP_RADIUS
            for (sx in intArrayOf(-1, 1)) {
                for (sz in intArrayOf(-1, 1)) {
                    val trap = CylinderNode(
                        engine = sceneView.engine,
                        radius = TRAP_RADIUS,
                        height = trapHeight,
                        sideCount = 16,
                        materialInstance = trapMaterial
                    ).apply {
                        position = Position(sx * trapInsetX, trapY, sz * trapInsetZ)
                    }
                    sceneView.addChildNode(trap)
                    trapEntries.add(TrapEntry(trap, sx, sz))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Room traps not added", t)
        }
        rebakeRoomTextures()
    }

    // --- Sprites: shadow disc + selection ring bitmaps -------------------

    /** Studio dressing for the front wall (behind the listener): a large
     *  tinted control-room window split in three panes by mullions, with a
     *  sill and reflection streaks, plus a framed door on the listener's
     *  right. Everything is parented under the wall plane so the dollhouse
     *  toggle hides it together with the wall. Glass and frames are lit
     *  materials (they dim with lights-off); the glossy glass picks up the
     *  IBL and lamps, the streak overlay sells the pane. */
    private fun addStudioFrontWall(wall: PlaneNode) {
        val ml = sceneView.materialLoader
        val glassMat  = ml.createColorInstance(Color.rgb(14, 20, 26), 0f, 0.06f, 0.9f)
        val frameMat  = ml.createColorInstance(Color.rgb(28, 29, 32), 0.6f, 0.4f, 0.5f)
        val doorMat   = ml.createColorInstance(Color.rgb(70, 52, 40), 0f, 0.65f, 0.3f)
        val handleMat = ml.createColorInstance(Color.rgb(182, 184, 188), 1f, 0.3f, 0.5f)

        fun box(w: Float, h: Float, d: Float, x: Float, y: Float, z: Float,
                mat: MaterialInstance) {
            wall.addChildNode(CubeNode(
                engine = sceneView.engine,
                size = Size(w, h, d),
                center = Position(0f, 0f, 0f),
                materialInstance = mat
            ).apply { position = Position(x, y, z) })
        }

        // --- Control-room window ---
        val floorY = WALL_LOCAL_FLOOR_Y
        val gBottom = floorY + GLASS_BOTTOM_ABOVE_FLOOR
        val gy = gBottom + GLASS_H / 2f
        val gx = GLASS_CENTER_X
        val t = GLASS_FRAME_T
        val fd = GLASS_FRAME_D
        // Pane, slightly recessed into the frame.
        box(GLASS_W, GLASS_H, 0.02f, gx, gy, 0.03f, glassMat)
        // Frame: top / bottom / sides.
        box(GLASS_W + 2f * t, t, fd, gx, gy + GLASS_H / 2f + t / 2f, fd / 2f, frameMat)
        box(GLASS_W + 2f * t, t, fd, gx, gy - GLASS_H / 2f - t / 2f, fd / 2f, frameMat)
        box(t, GLASS_H, fd, gx - GLASS_W / 2f - t / 2f, gy, fd / 2f, frameMat)
        box(t, GLASS_H, fd, gx + GLASS_W / 2f + t / 2f, gy, fd / 2f, frameMat)
        // Two mullions → three panes.
        box(t * 0.6f, GLASS_H, fd * 0.8f, gx - GLASS_W / 6f, gy, fd * 0.4f, frameMat)
        box(t * 0.6f, GLASS_H, fd * 0.8f, gx + GLASS_W / 6f, gy, fd * 0.4f, frameMat)
        // Sill: a shallow ledge under the window.
        box(GLASS_W + 2f * t + 0.10f, 0.04f, 0.22f, gx, gBottom - t - 0.02f, 0.11f, frameMat)
        // Reflection streaks just in front of the pane.
        val streaks = buildGlassStreakBitmap()
        glassStreakBitmap = streaks
        wall.addChildNode(ImageNode(
            materialLoader = ml,
            bitmap = streaks,
            size = Size(GLASS_W, GLASS_H, 0f)
        ).apply { position = Position(gx, gy, 0.045f) })

        // --- Door (listener's right = world -X) ---
        val dx = DOOR_CENTER_X
        val dy = floorY + DOOR_H / 2f
        val dt = DOOR_FRAME_T
        val dfd = DOOR_FRAME_D
        box(DOOR_W, DOOR_H, DOOR_D, dx, dy, DOOR_D / 2f, doorMat)
        // Frame: top + sides (no threshold bar).
        box(DOOR_W + 2f * dt, dt, dfd, dx, floorY + DOOR_H + dt / 2f, dfd / 2f, frameMat)
        box(dt, DOOR_H, dfd, dx - DOOR_W / 2f - dt / 2f, dy, dfd / 2f, frameMat)
        box(dt, DOOR_H, dfd, dx + DOOR_W / 2f + dt / 2f, dy, dfd / 2f, frameMat)
        // Narrow vision panel in the door.
        box(0.22f, 0.70f, 0.012f, dx, floorY + 1.45f, DOOR_D + 0.006f, glassMat)
        // Lever handle on the side away from the hinge (towards the window).
        box(0.16f, 0.03f, 0.03f, dx + DOOR_W / 2f - 0.16f, floorY + 1.0f, DOOR_D + 0.04f, handleMat)
        box(0.03f, 0.03f, 0.04f, dx + DOOR_W / 2f - 0.10f, floorY + 1.0f, DOOR_D + 0.02f, handleMat)
    }

    /** Transparent bitmap with soft diagonal white bands plus a faint top
     *  sheen: the classic "glass" read, drawn over the dark pane. Aspect
     *  matches the window so the bands are not stretched. */
    private fun buildGlassStreakBitmap(): Bitmap {
        val w = 1024
        val h = (w * GLASS_H / GLASS_W).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Faint sheen fading down from the top edge.
        c.drawRect(0f, 0f, w.toFloat(), h * 0.6f, Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.6f,
                Color.argb(22, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        })
        // Diagonal bands: (centre as a fraction of the width, width px, peak alpha).
        val bands = listOf(
            Triple(0.14f, 70f, 40),
            Triple(0.21f, 22f, 32),
            Triple(0.58f, 120f, 24),
            Triple(0.68f, 30f, 36),
            Triple(0.90f, 50f, 22)
        )
        c.save()
        c.skew(-0.45f, 0f)
        c.translate(h * 0.45f * 0.5f, 0f)
        for ((fx, bw, a) in bands) {
            val cx = fx * w
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(cx - bw / 2f, 0f, cx + bw / 2f, 0f,
                    intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(a, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(cx - bw / 2f, 0f, cx + bw / 2f, h.toFloat(), paint)
        }
        c.restore()
        return bmp
    }

    private fun prepareBitmaps() {
        // Interpolated tiers from floor to ceiling height: the peak alpha
        // drops (strong -> faint) and the soft ramp shortens (diffuse ->
        // tighter) as the stem rises.
        val n = SHADOW_TIER_COUNT
        shadowBitmaps = (0 until n).map { i ->
            val t = i / (n - 1f)
            val peak = lerp(170f, 45f, t).toInt()
            val coreStop = lerp(0.65f, 0.42f, t)
            buildRadialShadow(
                128,
                intArrayOf(peak, (peak * 0.8f).toInt(), (peak * 0.3f).toInt(), 0),
                floatArrayOf(0f, 0.25f, coreStop, 1f)
            )
        }
        headShadowBitmap = buildRadialShadow(
            256,
            intArrayOf(140, 112, 42, 0),
            floatArrayOf(0f, 0.30f, 0.62f, 1f)
        )
        ringBitmap       = buildRingBitmap()
        omniBitmap       = buildOmniGlow()
    }

    /** Soft accent glow marking the omnipresent stem (double tap). Same
     *  billboard pattern as the selection ring; radial falloff so it reads
     *  as a halo instead of a second ring. */
    private fun buildOmniGlow(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = roomAccent
        val r = Color.red(accent); val g = Color.green(accent); val b = Color.blue(accent)
        val grad = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.argb(190, r, g, b), Color.argb(120, r, g, b),
                        Color.argb(45, r, g, b), Color.argb(0, r, g, b)),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint().apply {
            shader = grad
            isAntiAlias = true
        })
        return bmp
    }

    /** Darkened copy of a stem icon for the muted state — flat-mode
     *  parity: the flat list dims icons to ~35% brightness. */
    private fun buildDimmedIcon(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cm = ColorMatrix(floatArrayOf(
            0.35f, 0f,    0f,    0f, 0f,
            0f,    0.35f, 0f,    0f, 0f,
            0f,    0f,    0.35f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ))
        c.drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return bmp
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Tileable fractal value noise in [-1, 1], [SURFACE_TEX_SIZE]². Each
     *  octave's lattice wraps at the tile edge, so the texture repeats
     *  seamlessly. [baseCells] = lattice cells of the first octave. */
    private fun fbmNoise(baseCells: Int, octaves: Int, seed: Long): FloatArray {
        val size = SURFACE_TEX_SIZE
        val out = FloatArray(size * size)
        val rnd = java.util.Random(seed)
        var cells = baseCells
        var amp = 1f
        var total = 0f
        repeat(octaves) {
            val lat = FloatArray(cells * cells) { rnd.nextFloat() }
            val scale = cells.toFloat() / size
            for (y in 0 until size) {
                val fy = y * scale
                val y0 = fy.toInt()
                val y1 = (y0 + 1) % cells
                val ty = (fy - y0).let { it * it * (3f - 2f * it) }
                for (x in 0 until size) {
                    val fx = x * scale
                    val x0 = fx.toInt()
                    val x1 = (x0 + 1) % cells
                    val tx = (fx - x0).let { it * it * (3f - 2f * it) }
                    val a = lat[y0 * cells + x0]; val b = lat[y0 * cells + x1]
                    val c = lat[y1 * cells + x0]; val d = lat[y1 * cells + x1]
                    val top = a + (b - a) * tx
                    val bot = c + (d - c) * tx
                    out[y * size + x] += amp * (top + (bot - top) * ty)
                }
            }
            total += amp
            amp *= 0.5f
            cells *= 2
        }
        for (i in out.indices) out[i] = out[i] / total * 2f - 1f
        return out
    }

    /** Per-pixel grain in [-1, 1] (fixed seed → stable across re-tints). */
    private fun grainField(seed: Long): FloatArray {
        val rnd = java.util.Random(seed)
        return FloatArray(SURFACE_TEX_SIZE * SURFACE_TEX_SIZE) { rnd.nextFloat() * 2f - 1f }
    }

    /** Tileable cellular noise over [cells]² jittered points: per pixel the
     *  tone of the nearest cell in [-1, 1] and the F2-F1 edge distance (in
     *  cell units; small near the gaps between cells). */
    private fun cellularField(cells: Int, seed: Long, jitter: Float = 0.9f): Pair<FloatArray, FloatArray> {
        val size = SURFACE_TEX_SIZE
        val rnd = java.util.Random(seed)
        val n = cells * cells
        val px = FloatArray(n); val py = FloatArray(n); val tone = FloatArray(n)
        for (i in 0 until n) {
            px[i] = 0.5f + (rnd.nextFloat() - 0.5f) * jitter
            py[i] = 0.5f + (rnd.nextFloat() - 0.5f) * jitter
            tone[i] = rnd.nextFloat() * 2f - 1f
        }
        val value = FloatArray(size * size)
        val edge = FloatArray(size * size)
        val scale = cells.toFloat() / size
        for (y in 0 until size) {
            val fy = y * scale
            val cy = fy.toInt()
            for (x in 0 until size) {
                val fx = x * scale
                val cx = fx.toInt()
                var d1 = 9f; var d2 = 9f; var id = 0
                for (j in -1..1) for (i in -1..1) {
                    val gx = cx + i; val gy = cy + j
                    val wx = ((gx % cells) + cells) % cells
                    val wy = ((gy % cells) + cells) % cells
                    val k = wy * cells + wx
                    val dx = gx + px[k] - fx; val dy = gy + py[k] - fy
                    val d = dx * dx + dy * dy
                    if (d < d1) { d2 = d1; d1 = d; id = k } else if (d < d2) d2 = d
                }
                value[y * size + x] = tone[id]
                edge[y * size + x] = sqrt(d2) - sqrt(d1)
            }
        }
        return Pair(value, edge)
    }

    /** Fine agglomerated cork (deviation field, brightness = 1 + c·d):
     *  granule tones ±16%, dark gaps between granules, faint mottling and
     *  grain. Same recipe as the texture preview ("corcho-fino"). */
    private fun corkField(): FloatArray {
        val (value, edge) = cellularField(cells = 40, seed = 71L)
        val mottle = fbmNoise(baseCells = 4, octaves = 3, seed = 72L)
        val grain = grainField(73L)
        return FloatArray(value.size) { i ->
            val e = edge[i]
            val gap = if (e < 0.06f) -0.22f * (1f - e / 0.06f) else 0f
            0.16f * value[i] + gap + 0.05f * mottle[i] + 0.05f * grain[i]
        }
    }

    /** Loop-pile carpet (deviation field): staggered rows of small loops,
     *  faint mottling and strong per-pixel grain. Same recipe as the
     *  texture preview ("moqueta-bucle"). */
    private fun carpetLoopField(): FloatArray {
        val size = SURFACE_TEX_SIZE
        val mottle = fbmNoise(baseCells = 16, octaves = 2, seed = 31L)
        val grain = grainField(32L)
        val rows = 64f
        val twoPi = (2.0 * Math.PI).toFloat()
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            val ry = y * rows / size
            val shift = if (ry.toInt() % 2 == 1) 0.5f else 0f
            val sy = kotlin.math.sin(ry * twoPi)
            for (x in 0 until size) {
                val i = y * size + x
                val loop = kotlin.math.sin((x * rows / size + shift) * twoPi) * sy
                out[i] = 0.07f * loop + 0.05f * mottle[i] + 0.14f * grain[i]
            }
        }
        return out
    }

    /** Cached deviation field of [pattern]. */
    private fun fieldFor(pattern: SurfacePattern): FloatArray = patternFields.getOrPut(pattern) {
        when (pattern) {
            SurfacePattern.CORK -> corkField()
            SurfacePattern.PLASTER -> plasterField()
            SurfacePattern.FOAM -> foamWedgeField()
            SurfacePattern.WOOD_SLATS -> woodField(vertical = true)
            SurfacePattern.FELT -> feltField()
            SurfacePattern.CARPET_LOOP -> carpetLoopField()
            SurfacePattern.CARPET_PLUSH -> carpetPlushField()
            SurfacePattern.WOOD_PLANKS -> woodField(vertical = false)
            SurfacePattern.TERRAZZO -> terrazzoField()
        }
    }

    /** [pattern]'s field with the surface's blur and sharpen applied
     *  (walls/ceiling or [floor]). Unfiltered = the cached field itself. */
    private fun surfaceField(pattern: SurfacePattern, floor: Boolean): FloatArray {
        val blur = if (floor) tuning.floorBlurPx else tuning.wallBlurPx
        val sharpen = if (floor) tuning.floorSharpen else tuning.wallSharpen
        var f = fieldFor(pattern)
        if (blur > 0) f = boxBlurField(boxBlurField(f, blur), blur)
        if (sharpen > 0f) {
            val base = f
            val soft = boxBlurField(base, 1)
            f = FloatArray(base.size) { i -> base[i] + sharpen * (base[i] - soft[i]) }
        }
        return f
    }

    /** Separable box blur of a tileable field (wraps at the edges, so the
     *  result still tiles seamlessly). */
    private fun boxBlurField(src: FloatArray, radius: Int): FloatArray {
        val n = SURFACE_TEX_SIZE
        val span = (2 * radius + 1).toFloat()
        val tmp = FloatArray(n * n)
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f
            for (k in -radius..radius) s += src[y * n + (x + k + n) % n]
            tmp[y * n + x] = s / span
        }
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f
            for (k in -radius..radius) s += tmp[((y + k + n) % n) * n + x]
            out[y * n + x] = s / span
        }
        return out
    }

    /** Troweled plaster: broad soft clouds, faint trowel ridges, fine grain. */
    private fun plasterField(): FloatArray {
        val low = fbmNoise(baseCells = 3, octaves = 4, seed = 81L)
        val mid = fbmNoise(baseCells = 12, octaves = 3, seed = 82L)
        val grain = grainField(83L)
        return FloatArray(low.size) { i ->
            0.14f * low[i] + 0.06f * (0.5f - kotlin.math.abs(mid[i])) + 0.02f * grain[i]
        }
    }

    /** Pressed felt: dense fine fibres over soft density clouds. */
    private fun feltField(): FloatArray {
        val fiber = fbmNoise(baseCells = 96, octaves = 2, seed = 91L)
        val mid = fbmNoise(baseCells = 24, octaves = 2, seed = 92L)
        val low = fbmNoise(baseCells = 4, octaves = 3, seed = 93L)
        val grain = grainField(94L)
        return FloatArray(fiber.size) { i ->
            0.10f * fiber[i] + 0.06f * mid[i] + 0.05f * low[i] + 0.08f * grain[i]
        }
    }

    /** Wedge acoustic foam: 8×8 blocks per tile, 4 wedges per block, block
     *  orientation alternating as a checkerboard. Facets lit from above
     *  (horizontal ridges) or the side (vertical ridges), dark valleys and
     *  block seams, open-cell pore grain. */
    private fun foamWedgeField(): FloatArray {
        val size = SURFACE_TEX_SIZE
        val block = size / 8
        val grain = grainField(95L)
        val mottle = fbmNoise(baseCells = 8, octaves = 2, seed = 96L)
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                val u = (x % block).toFloat() / block
                val v = (y % block).toFloat() / block
                val horizontal = ((x / block) + (y / block)) % 2 == 0
                val t = ((if (horizontal) v else u) * 4f) % 1f
                val lit = if (horizontal) 0.18f else 0.10f
                var s = if (t < 0.5f) lit else -lit
                val valley = kotlin.math.min(t, 1f - t)
                if (valley < 0.08f) s -= 0.25f * (1f - valley / 0.08f)
                val seam = minOf(u, 1f - u, v, 1f - v)
                if (seam < 0.02f) s -= 0.2f
                out[i] = s + 0.07f * grain[i] + 0.03f * mottle[i]
            }
        }
        return out
    }

    /** Wood boards, 8 per tile. [vertical] = wall slats (full-height
     *  boards); otherwise floor planks along X, one tile-long plank per row
     *  (it wraps across the tile edge) with a staggered end joint.
     *  Per-board tone, warped grain streaks, dark gaps between boards. */
    private fun woodField(vertical: Boolean): FloatArray {
        val size = SURFACE_TEX_SIZE
        val board = size / 8
        val gapPx = 2
        val rnd = java.util.Random(if (vertical) 101L else 111L)
        val tones = FloatArray(8) { rnd.nextFloat() * 2f - 1f }
        val joints = IntArray(8) { rnd.nextInt(size) }
        val warp = fbmNoise(baseCells = 4, octaves = 3, seed = if (vertical) 102L else 112L)
        val grain = grainField(if (vertical) 103L else 113L)
        val twoPi = (2.0 * Math.PI).toFloat()
        val out = FloatArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = y * size + x
                // across = coordinate across the boards, along = along them.
                val across = if (vertical) x else y
                val along = if (vertical) y else x
                val row = across / board
                val gap = (across % board) < gapPx ||
                        (!vertical && kotlin.math.abs(along - joints[row]) < gapPx)
                val streak = kotlin.math.sin((across * 48f / size + 2f * warp[i]) * twoPi)
                out[i] = if (gap) -0.35f
                else 0.08f * tones[row] + 0.06f * streak + 0.03f * grain[i]
            }
        }
        return out
    }

    /** Short-pile plush carpet: fine pile over soft shading patches. */
    private fun carpetPlushField(): FloatArray {
        val pile = fbmNoise(baseCells = 48, octaves = 2, seed = 121L)
        val patch = fbmNoise(baseCells = 5, octaves = 3, seed = 122L)
        val grain = grainField(123L)
        return FloatArray(pile.size) { i ->
            0.12f * grain[i] + 0.07f * pile[i] + 0.06f * patch[i]
        }
    }

    /** Terrazzo: large and small stone chips (shrunk cellular cells, some
     *  cells left empty) set in a fine-grained cement. */
    private fun terrazzoField(): FloatArray {
        val (bigTone, bigEdge) = cellularField(cells = 18, seed = 131L)
        val (smallTone, smallEdge) = cellularField(cells = 52, seed = 132L)
        val cement = fbmNoise(baseCells = 8, octaves = 3, seed = 133L)
        val grain = grainField(134L)
        return FloatArray(bigTone.size) { i ->
            when {
                bigEdge[i] > 0.12f && bigTone[i] > -0.3f -> 0.28f * bigTone[i]
                smallEdge[i] > 0.18f && smallTone[i] > 0.2f -> 0.30f * smallTone[i]
                else -> 0.03f * cement[i] + 0.04f * grain[i]
            }
        }
    }

    /** Bakes [tint] modulated by [field] (brightness = 1 + contrast·d) into
     *  an opaque bitmap. */
    private fun bakeSurface(field: FloatArray, tint: Int, contrast: Float, repeatY: Int = 1): Bitmap {
        val size = SURFACE_TEX_SIZE
        val r = Color.red(tint); val g = Color.green(tint); val b = Color.blue(tint)
        val tile = size * size
        val px = IntArray(tile * repeatY)
        for (i in px.indices) {
            val f = 1f + contrast * field[i % tile]
            px[i] = Color.rgb(
                (r * f).toInt().coerceIn(0, 255),
                (g * f).toInt().coerceIn(0, 255),
                (b * f).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, size, size * repeatY, Bitmap.Config.ARGB_8888)
    }

    /** Acoustic panel texture: one tile per face in the panel color. */
    private fun bakePanelSurface(): Bitmap = tuning.panelPattern.let {
        bakeSurface(surfaceField(it, floor = false), PANEL_COLOR, it.wallContrast)
    }

    /** Bass trap texture: the cylinder maps one texture over its whole
     *  side, so the tile is repeated vertically to match the side's aspect
     *  (circumference : height) and keep the pattern undistorted. */
    private fun bakeTrapSurface(): Bitmap = tuning.trapPattern.let {
        bakeSurface(surfaceField(it, floor = false), TRAP_COLOR, it.wallContrast, trapRepeats())
    }

    private fun trapRepeats(): Int {
        val circumference = 2f * Math.PI.toFloat() * TRAP_RADIUS
        return kotlin.math.round((ROOM_CEIL_Y - ROOM_FLOOR_Y) / circumference).toInt().coerceAtLeast(1)
    }

    /** Soft radial disc used for ground shadows. [alphas] are the black
     *  shadow alpha values per stop; [stops] shape the falloff — a long
     *  ramp reads as a diffuse wash, a short one as a tight disc. */
    private fun buildRadialShadow(size: Int, alphas: IntArray, stops: FloatArray): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val grad = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            alphas.map { Color.argb(it, 0, 0, 0) }.toIntArray(), stops,
            Shader.TileMode.CLAMP
        )
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint().apply {
            shader = grad
            isAntiAlias = true
        })
        return bmp
    }

    private fun buildRingBitmap(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = roomAccent
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = accent
            strokeWidth = 6f
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)
        return bmp
    }

    // --- Head -------------------------------------------------------------

    private fun loadHead() {
        try {
            val loader = sceneView.modelLoader
            val instance = loader.createModelInstance(HEAD_ASSET)
            val node = ModelNode(
                modelInstance = instance,
                scaleToUnits = HEAD_MODEL_SCALE_UNITS
            ).apply {
                // Seat the model base exactly on the floor plane regardless
                // of where the glb pivot sits. The asset bounding box is in
                // unscaled local space, so the pivot offset is multiplied
                // by the node scale (applied by the ctor before this block).
                val bb = boundingBox
                val localBottomY = (bb.center[1] - bb.halfExtent[1]) * scale.y
                position = Position(0f, ROOM_FLOOR_SURFACE_Y - localBottomY, 0f)
                rotation = Rotation(0f, 0f, 0f)
            }
            sceneView.addChildNode(node)
            headNode = node

            // Static soft blob under the bust. The GLB has no real-time
            // shadow (shadow casting is off for performance), so a baked
            // radial disc anchors the model to the floor and breaks the
            // floating look. Diameter follows the model's own footprint
            // (bbox width/depth, scaled); the halo multiplier must clear
            // the bust's pedestal — a shadow hugging the base stays
            // hidden underneath it from the default camera angle.
            headShadowBitmap?.let { bmp ->
                try {
                    val bb = node.boundingBox
                    val diameter = ((bb.halfExtent[0] + bb.halfExtent[2]) * node.scale.x) * 2.0f
                    Log.d(TAG, "head shadow diameter=$diameter")
                    val sh = ImageNode(
                        materialLoader = sceneView.materialLoader,
                        bitmap = bmp
                    ).apply {
                        rotation = Rotation(-90f, 0f, 0f)
                        position = Position(0f, ROOM_FLOOR_SURFACE_Y + 0.015f, 0f)
                        scale = Scale(diameter, diameter, 1f)
                    }
                    sceneView.addChildNode(sh)
                    headShadowNode = sh
                } catch (t: Throwable) {
                    Log.e(TAG, "head shadow failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load head model at $HEAD_ASSET", t)
        }
    }

    // --- Camera -----------------------------------------------------------

    private fun applyCameraPose() {
        val az = Math.toRadians(azimuthDeg.toDouble()).toFloat()
        val pi = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val r  = distance
        val targetY = HEAD_CENTER_Y
        val x  = r * cos(pi) * sin(az)
        val y  = targetY + r * sin(pi)
        val z  = r * cos(pi) * cos(az)
        try {
            val cam = sceneView.cameraNode
            cam.position = Position(x, y, z)
            cam.lookAt(Position(0f, targetY, 0f))
        } catch (t: Throwable) {
            Log.w(TAG, "cameraNode not writable", t)
        }
        rebuildManipulator(x, y, z)
    }

    /** Builds the orbit manipulator with [x,y,z] as its home pose. The
     *  manipulator keeps its own internal spherical state and never reads
     *  the camera back, so any external camera move (the room clamp) must
     *  be followed by a rebuild from the new pose — otherwise the next
     *  gesture computes from the stale state and orbit/zoom read as dead
     *  or jumping. Building is cheap: pure CPU state, no GPU resources. */
    private fun rebuildManipulator(x: Float, y: Float, z: Float): com.google.android.filament.utils.Manipulator? {
        return try {
            val vw = sceneView.width.coerceAtLeast(1)
            val vh = sceneView.height.coerceAtLeast(1)
            // Orbit speed intentionally low — a fast orbit reads as
            // twitchy on a small phone screen where the finger travel
            // covers half the viewport. Filament's Manipulator does not
            // expose bounds, so the "no wall clip" guarantee comes from
            // the wide room + soft clamp below.
            val manip = com.google.android.filament.utils.Manipulator.Builder()
                .viewport(vw, vh)
                .targetPosition(0f, HEAD_CENTER_Y, 0f)
                .orbitHomePosition(x, y, z)
                .orbitSpeed(0.003f, 0.003f)
                .zoomSpeed(0.035f)
                .build(com.google.android.filament.utils.Manipulator.Mode.ORBIT)
            // While interaction is disabled the manipulator stays detached —
            // re-attaching here would silently re-enable orbit/zoom.
            if (interactionEnabled) sceneView.cameraManipulator = manip
            manip
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to attach camera manipulator", t)
            null
        }
    }

    /** Rebuilds the orbit manipulator from [x,y,z] and re-arms an active
     *  single-finger grab so an orbit drag pinned against a room boundary
     *  keeps responding instead of freezing until the next touch. The
     *  gesture detector's original grab lives on the previous manipulator
     *  instance, which the rebuild discards; a fresh grabBegin at the last
     *  known finger position restores equivalent incremental orbiting.
     *
     *  Coordinate space: the detector feeds the manipulator an INVERTED Y
     *  (TouchPair builds Float2(x, height - y)), and OrbitManipulator
     *  computes grab deltas ABSOLUTELY from the grabBegin point — so the
     *  re-armed grab must use the same inverted space. Passing the raw
     *  MotionEvent Y makes the next grabUpdate see a delta of up to the
     *  full screen height and teleports the camera to the orbit pole. */
    private fun resyncManipulator(x: Float, y: Float, z: Float) {
        val manip = rebuildManipulator(x, y, z) ?: return
        if (touchCount == 1 && draggingSprite == null) {
            val h = sceneView.height.coerceAtLeast(1)
            try { manip.grabBegin(lastTouchX, h - lastTouchY, false) } catch (_: Throwable) {}
        }
    }

    fun setCameraPose(azimuthDeg: Float, pitchDeg: Float, distance: Float) {
        this.azimuthDeg = azimuthDeg
        this.pitchDeg   = pitchDeg
        this.distance   = distance
        applyCameraPose()
    }

    /** Chest-height floor for the camera. Filament's Manipulator has no
     *  built-in bounds, so once per frame the camera's actual position is
     *  checked: if it dipped below CHEST_Y the pose is rewritten keeping
     *  the SAME distance and azimuth (only the pitch changes) — capping
     *  the height while keeping the horizontal radius would collapse the
     *  total distance and read as a violent zoom. The rewrite is followed
     *  by a manipulator resync because SceneView pushes the manipulator's
     *  internal pose onto the camera every frame and that state never
     *  reads the camera back. */
    private fun updateCameraBounds() {
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { return }
        val p = cam.position
        if (p.y >= CHEST_Y) return
        val ty = HEAD_CENTER_Y
        val dx0 = p.x
        val dy0 = p.y - ty
        val dz0 = p.z
        val r = sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0)
        val rh0 = sqrt(dx0 * dx0 + dz0 * dz0)
        val az = if (rh0 > 1e-4f) atan2(dx0.toDouble(), dz0.toDouble()).toFloat() else 0f
        // Pitch needed for the camera to sit exactly at CHEST_Y at the
        // same radius. r > (ty - CHEST_Y) is guaranteed here, so the
        // asin argument stays in range; clamp anyway as a guard.
        val sinP = ((CHEST_Y - ty) / r).coerceIn(-1f, 1f)
        val cosP = sqrt(1.0 - sinP * sinP)
        val nx = (r * cosP * sin(az)).toFloat()
        val nz = (r * cosP * cos(az)).toFloat()
        try {
            cam.position = Position(nx, CHEST_Y, nz)
            cam.lookAt(Position(0f, ty, 0f))
        } catch (_: Throwable) {}
        resyncManipulator(nx, CHEST_Y, nz)
    }

    /** Dollhouse visibility: any room surface the camera has crossed
     *  hides itself so orbiting out of the box never clips geometry or
     *  shows a wall from behind. Each surface reappears once the camera
     *  is back inside by the hysteresis band. */
    private fun updateRoomVisibility() {
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { return }
        val p = cam.position
        setSurfaceVisible(ceilingNode, p.y, ROOM_CEIL_Y)
        // Walls carry fixtures protruding into the room (panels, screen,
        // window sill, door handle): hide them before the camera reaches
        // that depth, or it flies through the fixtures and they fill the view.
        setSurfaceVisible(wallPlusX, p.x, ROOM_HALF_X - WALL_FIXTURE_CLEARANCE)
        setSurfaceVisible(wallMinusX, -p.x, ROOM_HALF_X - WALL_FIXTURE_CLEARANCE)
        setSurfaceVisible(wallPlusZ, p.z, ROOM_HALF_Z - WALL_FIXTURE_CLEARANCE)
        setSurfaceVisible(wallMinusZ, -p.z, ROOM_HALF_Z - WALL_FIXTURE_CLEARANCE)
        // Bass traps live at scene root; each hides when either of the
        // two walls sharing its corner is culled (else it would float in
        // the open air above the missing wall).
        for (t in trapEntries) {
            val wx = if (t.sx > 0) wallPlusX else wallMinusX
            val wz = if (t.sz > 0) wallPlusZ else wallMinusZ
            val target = (wx?.isVisible ?: true) && (wz?.isVisible ?: true)
            if (t.node.isVisible != target) t.node.isVisible = target
        }
    }

    private fun setSurfaceVisible(node: PlaneNode?, camCoord: Float, plane: Float) {
        val n = node ?: return
        val target = when {
            camCoord > plane + SURFACE_HIDE_EPS -> false
            camCoord < plane - SURFACE_SHOW_EPS -> true
            else -> n.isVisible // hold current state inside the band
        }
        if (target != n.isVisible) n.isVisible = target
    }

    // --- Lighting ---------------------------------------------------------

    private fun snapshotBaseLightIntensities() {
        try {
            val ibl = sceneView.indirectLight
            // Adopt the baseline exactly once: the first healthy read is the
            // pipeline default. Re-reading afterwards would capture values
            // written by setLightIntensity itself (e.g. a 0.2 dim) and lock
            // the environment light at the dimmed level.
            // A read of 0 means the pipeline is not warm yet — keep NaN so
            // the next call retries.
            if (baseIndirectIntensity.isNaN() && ibl != null && ibl.intensity > 0f) {
                baseIndirectIntensity = ibl.intensity
            }
        } catch (_: Throwable) {}
        // Main light is disabled (see configureSceneLighting) — the four
        // ceiling lamps carry the scene now. Their baselines are stored
        // as they get added in addCornerLight().
    }

    /** Scale scene lighting to [fraction] of the baseline captured at
     *  setup. fraction = 1 → full brightness (lights on); fraction = 0.20
     *  → dimly lit ("lights off"). Scales both the ceiling corner lamps
     *  (the primary source in this scene) and the sceneview IBL fill.
     *  Lazily snapshots baselines so a caller that fires before the
     *  post-setup pass still gets the correct dim on the first render. */
    fun setLightIntensity(fraction: Float) {
        val k = fraction.coerceIn(0f, 1f)
        currentLightIntensity = k
        // Self-healing: if the baseline was never captured (NaN) or was
        // captured while degenerate (0), retry — the IBL becomes valid
        // once the GPU pipeline has processed the environment.
        if (baseIndirectIntensity.isNaN() || baseIndirectIntensity <= 0f) {
            snapshotBaseLightIntensities()
        }
        try {
            val ibl = sceneView.indirectLight
            if (ibl != null && !baseIndirectIntensity.isNaN()) {
                ibl.setIntensity(baseIndirectIntensity * tuning.ambientFactor * k)
            }
        } catch (_: Throwable) {}
        for (i in cornerLights.indices) {
            try {
                cornerLights[i].intensity = cornerBaseIntensity[i] * k
            } catch (_: Throwable) {}
        }
    }

    /** Convenience for the switch: fade illumination between on/off with
     *  a short animator running on the UI thread. */
    /** Full frame rate for a while (the view idles at a low rate). */
    private fun wake(ms: Long = 1_500L) {
        (sceneView as? FisionSceneView)?.keepActive(ms)
    }

    fun animateLights(on: Boolean, durationMs: Long = 260L) {
        wake(durationMs + 300L)
        val start = currentLightIntensity
        val end = if (on) LIGHT_INTENSITY_ON else LIGHT_INTENSITY_OFF
        val anim = android.animation.ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            addUpdateListener { setLightIntensity(it.animatedValue as Float) }
        }
        anim.start()
    }

    // --- Stem sprites -----------------------------------------------------

    fun setStems(items: List<StemPose>) {
        clearSprites()
        // New nodes start unrotated (facing +Z, i.e. seen from behind by the
        // default camera): force the next billboard pass even if the camera
        // has not moved since the previous stem set.
        lastCamX = Float.NaN
        wake()
        items.forEach { p ->
            try {
                val node = ImageNode(
                    materialLoader = sceneView.materialLoader,
                    bitmap = p.bitmap
                ).apply {
                    scale = Scale(SPRITE_SIZE, SPRITE_SIZE, 1f)
                }
                sceneView.addChildNode(node)

                val shadow: ImageNode? = shadowBitmaps.firstOrNull()?.let { bmp ->
                    ImageNode(
                        materialLoader = sceneView.materialLoader,
                        bitmap = bmp
                    ).apply {
                        // Horizontal quad on the floor.
                        rotation = Rotation(-90f, 0f, 0f)
                        scale = Scale(SHADOW_SIZE, SHADOW_SIZE, 1f)
                    }.also { sceneView.addChildNode(it) }
                }
                val ring: ImageNode? = ringBitmap?.let { bmp ->
                    ImageNode(
                        materialLoader = sceneView.materialLoader,
                        bitmap = bmp
                    ).apply {
                        scale = Scale(RING_SIZE, RING_SIZE, 1f)
                        isVisible = false
                    }.also { sceneView.addChildNode(it) }
                }
                val omni: ImageNode? = omniBitmap?.let { bmp ->
                    ImageNode(
                        materialLoader = sceneView.materialLoader,
                        bitmap = bmp
                    ).apply {
                        scale = Scale(OMNI_SIZE, OMNI_SIZE, 1f)
                        isVisible = false
                    }.also { sceneView.addChildNode(it) }
                }
                // Dimmed twin for the muted state (long press). Hidden
                // until setStemMuted swaps visibility.
                val dimmed = buildDimmedIcon(p.bitmap).also { dimmedBitmaps.add(it) }
                val mutedNode: ImageNode = ImageNode(
                    materialLoader = sceneView.materialLoader,
                    bitmap = dimmed
                ).apply {
                    scale = Scale(SPRITE_SIZE, SPRITE_SIZE, 1f)
                    isVisible = false
                }.also { sceneView.addChildNode(it) }
                val distClamp = p.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
                val s = StemSprite(
                    p.id, node, shadow, ring, omni, mutedNode,
                    p.azimuthRad, p.heightM.coerceIn(H_MIN_M, H_MAX_M),
                    distClamp
                )
                applySpritePose(s)
                sprites[p.id] = s
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to add sprite ${p.id}", t)
            }
        }
        ensureBillboardFrameListener()
    }

    fun updateStemPose(id: String, azimuthRad: Float, heightM: Float, distance: Float) {
        val s = sprites[id] ?: return
        s.azimuthRad = azimuthRad
        s.heightM = heightM.coerceIn(H_MIN_M, H_MAX_M)
        s.distance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        applySpritePose(s)
    }

    /** Update just the horizontal radius for one sprite. Drives the
     *  vertical slider in scene mode: the sprite moves radially in the
     *  XY plane, its height stays untouched. */
    fun updateStemDistance(id: String, distance: Float) {
        val s = sprites[id] ?: return
        s.distance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        applySpritePose(s)
        val nowNs = System.nanoTime()
        val lastNs = lastEngineUpdateNs[id] ?: 0L
        if (nowNs - lastNs >= ENGINE_UPDATE_INTERVAL_NS) {
            lastEngineUpdateNs[id] = nowNs
            dragListener?.onStemPoseChanged(
                s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ false
            )
        }
    }

    /** Emit the settle callback for one stem — used when a slider drag
     *  releases and the host wants Room to persist the new distance. */
    fun commitStemDistance(id: String) {
        val s = sprites[id] ?: return
        dragListener?.onStemPoseChanged(
            s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ true
        )
    }

    fun getStemDistance(id: String): Float? = sprites[id]?.distance

    // Interaction gate: when the 3D audio switch is off the scene becomes
    // a static backdrop — no sprite dragging, no orbit/zoom. The camera
    // gesture detector is kept (its setter is private in SceneView); the
    // MANIPULATOR is what it drives, and it is null-safe on every call.
    private var interactionEnabled = true
    private var originalCameraManipulator: com.google.android.filament.utils.Manipulator? = null

    fun setInteractionEnabled(enabled: Boolean) {
        interactionEnabled = enabled
        try {
            if (enabled) {
                // Always rebuild from the camera's CURRENT pose. Restoring a
                // saved manipulator is wrong: the Manipulator keeps its own
                // internal spherical state and never reads the camera back,
                // so a stale instance (e.g. SceneView's constructor default,
                // built front-close at inflation time) teleports the camera
                // on the first orbit. The home pose is the only persistent
                // part, and it can be read from the camera at any moment.
                val p = try { sceneView.cameraNode.position } catch (_: Throwable) { null }
                if (p != null) rebuildManipulator(p.x, p.y, p.z)
                originalCameraManipulator = null
                sceneView.setOnTouchListener(null)
            } else {
                originalCameraManipulator = null
                sceneView.cameraManipulator = null
                // Consume every touch at the View level. SceneView's
                // onTouchEvent short-circuits when super.onTouchEvent
                // (the plain View listener path) returns true, so nothing —
                // orbit, zoom, sprite drag, tap — can fire while inert.
                sceneView.setOnTouchListener { _, _ -> true }
            }
        } catch (_: Throwable) {}
    }

    /** Current pose of one sprite — used by the host to animate resets
     *  from the live position instead of teleporting. */
    fun getStemPose(id: String): StemPose? {
        val s = sprites[id] ?: return null
        // bitmap is irrelevant here; reuse a zero-sized placeholder.
        return StemPose(s.id, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                s.azimuthRad, s.heightM, s.distance)
    }

    private fun applySpritePose(s: StemSprite) {
        wake()
        // Cylindrical pose: distance is the horizontal radius (XY plane),
        // heightM is the linear offset from the head centre.
        val sx = s.distance * sin(s.azimuthRad)
        var sy = HEAD_CENTER_Y + s.heightM
        val sz = s.distance * cos(s.azimuthRad)
        // Keep the sprite above the cubicle floor — the sphere can dip
        // past it when the user drags below the horizon at short distance.
        val minY = ROOM_FLOOR_Y + 0.20f
        if (sy < minY) sy = minY
        s.node.position = Position(sx, sy, sz)
        // Shadow: sits on the slab surface directly under the sprite.
        // It spreads and fades with height — a ceiling light casts a tight
        // dark disc on a low stem and a wide diffuse wash on a high one.
        s.shadow?.let { sh ->
            val height = max(0f, sy - ROOM_FLOOR_SURFACE_Y)
            val t = min(1f, height / SHADOW_TIER_HEIGHT)
            val k = 1f + t * 1.2f
            sh.position = Position(sx, ROOM_FLOOR_SURFACE_Y + 0.015f, sz)
            sh.scale = Scale(SHADOW_SIZE * k, SHADOW_SIZE * k, 1f)
            val tier = (t * (shadowBitmaps.size - 1) + 0.5f).toInt().coerceIn(0, shadowBitmaps.size - 1)
            if (sh.bitmap !== shadowBitmaps[tier]) sh.bitmap = shadowBitmaps[tier]
        }
        s.ring?.position = Position(sx, sy, sz)
        s.omni?.position = Position(sx, sy, sz)
        s.mutedNode.position = Position(sx, sy, sz)
    }

    private fun clearSprites() {
        sprites.values.forEach {
            runCatching { sceneView.removeChildNode(it.node) }
            runCatching { sceneView.removeChildNode(it.mutedNode) }
            it.shadow?.let { sh -> runCatching { sceneView.removeChildNode(sh) } }
            it.ring?.let   { rn -> runCatching { sceneView.removeChildNode(rn) } }
            it.omni?.let   { om -> runCatching { sceneView.removeChildNode(om) } }
        }
        sprites.clear()
        dimmedBitmaps.forEach { runCatching { it.recycle() } }
        dimmedBitmaps.clear()
        selectedId = null
    }

    private fun ensureBillboardFrameListener() {
        if (frameListenerAttached) return
        try {
            sceneView.onFrame = { _ ->
                framesRendered++
                checkWarm()
                checkFrameWaiters()
                if (danceActive) tickDance()
                updateCameraBounds()
                updateRoomVisibility()
                orientSpritesToCamera()
            }
            frameListenerAttached = true
        } catch (t: Throwable) {
            Log.w(TAG, "Frame listener not attached", t)
        }
    }

    private fun tickDance() {
        val nowNs = System.nanoTime()
        val dtSec = (nowNs - danceLastNs).coerceAtLeast(0L) / 1_000_000_000f
        danceLastNs = nowNs
        val twoPi = (2 * Math.PI).toFloat()
        for (id in danceStemIds) {
            val s = sprites[id] ?: continue
            if (draggingSprite === s) continue
            val factor = danceSpeedFactor[id] ?: 1f
            s.azimuthRad = (s.azimuthRad + danceSpeed * factor * twoPi * dtSec) % twoPi
            // Sine-wave stems bob vertically around chest height (the head
            // centre line), full range: the current Z position is ignored so
            // a stem parked near the ceiling or floor never gets a clipped
            // wave. The phase is independent of the orbit and advances at
            // per-stem speed in cycles/second (bottom-row slider).
            if (danceSineIds.contains(id)) {
                val speed = danceOscSpeeds[id] ?: DEFAULT_OSC_SPEED
                val ph = (danceOscPhase[id] ?: 0f) + twoPi * speed * dtSec
                danceOscPhase[id] = ph
                s.heightM = (DANCE_SINE_AMP * sin(ph)).coerceIn(H_MIN_M, H_MAX_M)
            }
            applySpritePose(s)
            val lastNs = lastEngineUpdateNs[id] ?: 0L
            if (nowNs - lastNs >= ENGINE_UPDATE_INTERVAL_NS) {
                lastEngineUpdateNs[id] = nowNs
                dragListener?.onStemPoseChanged(
                    s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ false
                )
            }
        }
    }

    // --- Touch: select-then-drag + offset raycast -----------------------

    private fun installTouchListener() {
        gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (dragMoved) return false
                gestureSprite?.let {
                    tapListener?.onSpriteTap(it.id, SpriteTap.TAP)
                    return true
                }
                return false
            }
        })
        // Long-press and double-tap are both handled in the touch hook
        // (longPressRunnable / lastTapUp* bookkeeping) — the GD timers
        // cannot coexist with the immediate drag arm, and the GD double-
        // tap tolerance (~touch slop) is too strict for on-screen sprites.
        gestureDetector?.setIsLongpressEnabled(false)

        // No camera pan: the orbit target stays on the listener, so the view
        // always matches what is heard and the home-pose constants stay valid.
        sceneView.cameraGestureDetector?.isPanEnabled = false

        // Route touch through sceneview's own onTouchEvent hook — it
        // executes before the camera manipulator's gesture detector and
        // returning true from it suppresses the orbit. This is more
        // reliable than View.setOnTouchListener on some devices where
        // dispatchTouchEvent flows around the listener.
        sceneView.onTouchEvent = fun(ev: MotionEvent, _: io.github.sceneview.collision.HitResult?): Boolean {
            // 3D audio off → no sprite dragging, no camera gestures.
            if (!interactionEnabled) return false
            gestureDetector?.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchCount = 1
                MotionEvent.ACTION_POINTER_DOWN -> touchCount++
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> touchCount = max(0, touchCount - 1)
            }
            lastTouchX = ev.x.toInt()
            lastTouchY = ev.y.toInt()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = pickSpriteAt(ev.x, ev.y)
                    gestureSprite = hit
                    draggingSprite = null
                    readyToDrag = (hit != null)
                    dragMoved = false
                    dragStartX = ev.x
                    dragStartY = ev.y
                    cancelLongPress()
                    longPressFired  = false
                    if (hit != null) {
                        val screen = projectWorldToScreen(
                            hit.node.position.x, hit.node.position.y, hit.node.position.z
                        )
                        if (screen != null) {
                            dragOffsetX = ev.x - screen.first
                            dragOffsetY = ev.y - screen.second
                        } else {
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                        }
                        // Hold-to-mute: fires after LONG_PRESS_MS of stillness,
                        // killed only by real movement (> touch slop). A drag
                        // may still start afterwards, matching flat mode.
                        val sprite = hit
                        longPressRunnable = Runnable {
                            if (!longPressFired) {
                                longPressFired = true
                                tapListener?.onSpriteTap(sprite.id, SpriteTap.LONG_PRESS)
                                try {
                                    sceneView.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                    )
                                } catch (_: Throwable) {}
                            }
                        }
                        sceneView.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    // Real movement (> touch slop) kills the pending long-
                    // press and flips dragMoved; sub-slop jitter does not —
                    // a clean tap must stay "not moved" so double-tap
                    // detection and the muted visual survive finger noise.
                    // The drag still arms on this first event for zero-
                    // latency response.
                    val mdx = ev.x - dragStartX
                    val mdy = ev.y - dragStartY
                    if (mdx * mdx + mdy * mdy > touchSlopPx * touchSlopPx && !dragMoved) {
                        cancelLongPress()
                        dragMoved = true
                    }
                    if (draggingSprite == null) {
                        if (!readyToDrag) return false
                        val target = gestureSprite ?: return false
                        draggingSprite = target
                        setStemSelected(target.id)
                        tapListener?.onSpriteTap(target.id, SpriteTap.TAP)
                        gestureDetector?.setIsLongpressEnabled(false)
                        try {
                            target.node.scale = Scale(SPRITE_SIZE * 1.25f, SPRITE_SIZE * 1.25f, 1f)
                            target.mutedNode.scale = Scale(SPRITE_SIZE * 1.25f, SPRITE_SIZE * 1.25f, 1f)
                        } catch (_: Throwable) {}
                        try {
                            sceneView.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK
                            )
                        } catch (_: Throwable) {}
                    }
                    val s = draggingSprite ?: return false
                    val hitX = ev.x - dragOffsetX
                    val hitY = ev.y - dragOffsetY
                    val solved = solveCylinderPose(hitX, hitY, s.distance, s.azimuthRad, s.heightM)
                    if (solved != null) {
                        s.azimuthRad = solved.first
                        s.heightM = solved.second
                    } else {
                        // Solver failed (point behind camera or singular
                        // Jacobian) — fall back to a camera-relative
                        // screen delta so the sprite still tracks the
                        // finger. Screen dx maps to azimuth, dy maps to
                        // elevation.
                        val dx = ev.x - dragStartX
                        val dy = ev.y - dragStartY
                        val kAz  = DRAG_FALLBACK_RAD_PER_PX
                        val kH   = DRAG_FALLBACK_M_PER_PX
                        s.azimuthRad += dx * kAz
                        s.heightM = (s.heightM - dy * kH)
                            .coerceIn(H_MIN_M, H_MAX_M)
                        // Consume the delta so it does not accumulate.
                        dragStartX = ev.x
                        dragStartY = ev.y
                    }
                    applySpritePose(s)
                    val nowNs = System.nanoTime()
                    val lastNs = lastEngineUpdateNs[s.id] ?: 0L
                    if (nowNs - lastNs >= ENGINE_UPDATE_INTERVAL_NS) {
                        lastEngineUpdateNs[s.id] = nowNs
                        dragListener?.onStemPoseChanged(
                            s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ false
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    val s = draggingSprite
                    if (s != null) {
                        // Bump is transient — restore the base scale on
                        // both the regular and the dimmed (muted) twin.
                        try {
                            s.node.scale = Scale(SPRITE_SIZE, SPRITE_SIZE, 1f)
                            s.mutedNode.scale = Scale(SPRITE_SIZE, SPRITE_SIZE, 1f)
                        } catch (_: Throwable) {}
                        if (dragMoved) {
                            dragListener?.onStemPoseChanged(
                                s.id, s.azimuthRad, atan2(s.heightM, s.distance), s.distance, /*isFinal=*/ true
                            )
                        }
                    }
                    // Own double-tap detection: two clean taps (no real
                    // drag) within the time + radius windows toggle the
                    // omnipresent state of the sprite under the second tap.
                    val gs = gestureSprite
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !dragMoved && gs != null) {
                        val now = ev.eventTime
                        val dx = ev.x - lastTapUpX
                        val dy = ev.y - lastTapUpY
                        if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS &&
                            dx * dx + dy * dy < doubleTapRadiusPx * doubleTapRadiusPx
                        ) {
                            tapListener?.onSpriteTap(gs.id, SpriteTap.DOUBLE_TAP)
                            lastTapUpTime = 0L
                        } else {
                            lastTapUpTime = now
                            lastTapUpX = ev.x
                            lastTapUpY = ev.y
                        }
                    }
                    draggingSprite = null
                    readyToDrag = false
                    gestureDetector?.setIsLongpressEnabled(true)
                    return s != null
                }
            }
            return false
        }
    }

    /** Drops the pending long-press runnable, if any. */
    private fun cancelLongPress() {
        longPressRunnable?.let { r ->
            try { sceneView.removeCallbacks(r) } catch (_: Throwable) {}
        }
        longPressRunnable = null
    }

    private fun pickSpriteAt(sx: Float, sy: Float): StemSprite? {
        val threshPx = 80f * activity.resources.displayMetrics.density
        var best: StemSprite? = null
        var bestD2 = threshPx * threshPx
        var projectedAny = false
        for (s in sprites.values) {
            val screen = projectWorldToScreen(s.node.position.x, s.node.position.y, s.node.position.z)
                ?: continue
            projectedAny = true
            val dx = screen.first - sx
            val dy = screen.second - sy
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; best = s }
        }
        if (!projectedAny && sprites.isNotEmpty()) {
            Log.w(TAG, "pickSpriteAt: no sprite projected (camera matrix?)")
        }
        return best
    }

    private fun projectWorldToScreen(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { return null }
        val filamentCam = try { cam.camera } catch (_: Throwable) { return null }
        val view = FloatArray(16); try { filamentCam.getViewMatrix(view) } catch (_: Throwable) { return null }
        val projD = DoubleArray(16); try { filamentCam.getProjectionMatrix(projD) } catch (_: Throwable) { return null }
        val proj = DoubleArray(16) { projD[it] }
        val viewD = DoubleArray(16) { view[it].toDouble() }
        val vx = viewD[0]*x + viewD[4]*y + viewD[8]*z  + viewD[12]
        val vy = viewD[1]*x + viewD[5]*y + viewD[9]*z  + viewD[13]
        val vz = viewD[2]*x + viewD[6]*y + viewD[10]*z + viewD[14]
        val vw = viewD[3]*x + viewD[7]*y + viewD[11]*z + viewD[15]
        val cx = proj[0]*vx + proj[4]*vy + proj[8]*vz  + proj[12]*vw
        val cy = proj[1]*vx + proj[5]*vy + proj[9]*vz  + proj[13]*vw
        val cw = proj[3]*vx + proj[7]*vy + proj[11]*vz + proj[15]*vw
        if (cw <= 0.0) return null
        val nx = cx / cw
        val ny = cy / cw
        val vwPx = sceneView.width.toFloat()
        val vhPx = sceneView.height.toFloat()
        val sxOut = ((nx * 0.5 + 0.5) * vwPx).toFloat()
        val syOut = ((1.0 - (ny * 0.5 + 0.5)) * vhPx).toFloat()
        return sxOut to syOut
    }

    /** Screen-space inverse of [projectWorldToScreen] on the pose cylinder.
     *  Newton iteration with a finite-difference Jacobian: converges the
     *  (azimuth, height) pair whose projected position matches the finger,
     *  at the sprite's current horizontal radius. Uses the same projection
     *  path as picking, so no matrix convention issues can arise. Returns
     *  null when the projection is unavailable or the Jacobian is singular
     *  (caller falls back to a screen-delta drag). */
    private fun solveCylinderPose(
        targetX: Float, targetY: Float, rh: Float,
        azInit: Float, hInit: Float
    ): Pair<Float, Float>? {
        val step = 0.01f
        var az = azInit
        var h = hInit
        for (it in 0 until 8) {
            val p0 = projectAt(az, h, rh) ?: return null
            var ex = p0.first - targetX
            var ey = p0.second - targetY
            val err = sqrt(ex * ex + ey * ey)
            if (err < 1.5f) break
            val pa = projectAt(az + step, h, rh) ?: return null
            val ph = projectAt(az, h + step, rh) ?: return null
            val j1x = (pa.first - p0.first) / step
            val j1y = (pa.second - p0.second) / step
            val j2x = (ph.first - p0.first) / step
            val j2y = (ph.second - p0.second) / step
            val det = j1x * j2y - j2x * j1y
            if (abs(det) < 1e-6f) return null
            // Damped Newton: halve the step while the residual grows.
            var scale = 1f
            var improved = false
            for (trial in 0 until 4) {
                val nAz = az + (ey * j2x - ex * j2y) / det * scale
                val nH  = h + (ex * j1y - ey * j1x) / det * scale
                val pn = projectAt(nAz, nH, rh) ?: return null
                val nx = pn.first - targetX
                val ny = pn.second - targetY
                val nErr = sqrt(nx * nx + ny * ny)
                if (nErr < err) {
                    az = nAz; h = nH; ex = nx; ey = ny
                    improved = true
                    break
                }
                scale *= 0.5f
            }
            if (!improved) break
        }
        return Pair(az, h.coerceIn(H_MIN_M, H_MAX_M))
    }

    private fun projectAt(az: Float, h: Float, rh: Float): Pair<Float, Float>? {
        val x = rh * sin(az)
        val y = HEAD_CENTER_Y + h
        val z = rh * cos(az)
        return projectWorldToScreen(x, y, z)
    }

    private fun orientSpritesToCamera() {
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { null } ?: return
        val cp = cam.position
        val camMoved = lastCamX.isNaN()
                || abs(cp.x - lastCamX) > CAM_MOVE_EPS
                || abs(cp.y - lastCamY) > CAM_MOVE_EPS
                || abs(cp.z - lastCamZ) > CAM_MOVE_EPS
        if (!camMoved && !danceActive && draggingSprite == null) return
        lastCamX = cp.x; lastCamY = cp.y; lastCamZ = cp.z
        for (s in sprites.values) {
            val p = s.node.position
            val dx = cp.x - p.x
            val dz = cp.z - p.z
            val yawDeg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            s.node.rotation = Rotation(0f, yawDeg, 0f)
            s.ring?.rotation = Rotation(0f, yawDeg, 0f)
            s.omni?.rotation = Rotation(0f, yawDeg, 0f)
            s.mutedNode.rotation = Rotation(0f, yawDeg, 0f)
        }
    }

    /** Persistent accent halo on the omnipresent stem (double tap).
     *  Orthogonal to the selection ring: a stem can be selected, omni,
     *  both, or neither. */
    fun setStemOmnipresent(id: String, on: Boolean) {
        sprites[id]?.omni?.isVisible = on
    }

    /** Debug: dump the LIVE camera pose in home spherical coordinates
     *  (azimuth / pitch / distance around the head centre, same convention
     *  as setCameraPose) so a preferred home view can be captured from
     *  logcat. Triggered by the scene info button. The cached azimuthDeg /
     *  pitchDeg / distance fields are NOT read — they only track explicit
     *  setCameraPose calls, not free orbit. */
    fun logCameraPose() {
        cameraPoseConstants()?.let { Log.i(TAG, "CamPose $it") }
    }

    /** Live camera pose as the orbit constants (azimuth / pitch / distance
     *  around the head target) plus the world position; null when the
     *  camera is not readable. Same convention as [applyCameraPose]. */
    fun cameraPoseConstants(): String? {
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { null } ?: return null
        val cp = cam.position
        val dx = cp.x
        val dy = cp.y - HEAD_CENTER_Y
        val dz = cp.z
        val r = sqrt(dx * dx + dy * dy + dz * dz)
        if (r < 0.01f) return null
        val az = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble()))
        val pitch = Math.toDegrees(asin((dy / r).coerceIn(-1f, 1f)).toDouble())
        return String.format(java.util.Locale.US,
            "DEF_AZIMUTH_DEG=%.1f DEF_PITCH_DEG=%.1f DEF_DISTANCE=%.2f  world=(%.2f, %.2f, %.2f)",
            az, pitch, r, cp.x, cp.y, cp.z)
    }

    /** Debug: camera back to the shipped home pose. */
    fun resetCameraPose() {
        setCameraPose(DEF_AZIMUTH_DEG, DEF_PITCH_DEG, DEF_DISTANCE)
    }

    /** Debug "copy values": tuning constants + lens + live camera pose. */
    fun debugDescribe(): String =
        tuning.describe() + "\n" + (cameraPoseConstants() ?: "camera pose unavailable")

    // --- Song card --------------------------------------------------------

    /** Repaint the back-wall screen with the current song's card. Composed
     *  once here and left static — the image node never repaints on its
     *  own, so an idle scene costs nothing beyond the constant textured
     *  quad. Safe to call before setup(): the values are cached and applied
     *  when the screen node exists. */
    fun setSongCard(coverFile: java.io.File?, title: String, artist: String) {
        pendingCover = coverFile
        pendingTitle = title
        pendingArtist = artist
        if (screenImage == null) return
        // Cover decode, card and backlight run on the bake thread; the
        // latest request wins.
        val gen = ++cardBakeGen
        val fallback = roomAccent
        bakeAsync({
            val cover = decodeCover(coverFile)
            Triple(buildSongCardBitmap(cover, title, artist),
                buildBacklightBitmap(cover, fallback), cover)
        }) { (card, glowBmp, cover) ->
            if (gen != cardBakeGen) return@bakeAsync
            val img = screenImage ?: return@bakeAsync
            screenBitmap = card
            // ImageNode's bitmap setter re-uploads only mip level 0; the
            // texture is built with a full mip chain, so the lower levels
            // (the ones sampled at room distance) would keep showing the
            // placeholder. Regenerate the chain after the swap. The previous
            // bitmap is not recycled here: the upload is asynchronous and
            // may still read it.
            try {
                img.bitmap = card
                img.texture.generateMipmaps(sceneView.engine)
            } catch (t: Throwable) {
                Log.w(TAG, "setSongCard bitmap swap failed", t)
            }
            backlightCover = cover
            backlightNode?.let { glow ->
                runCatching {
                    glow.bitmap = glowBmp
                    glow.texture.generateMipmaps(sceneView.engine)
                }.onFailure { Log.w(TAG, "backlight swap failed", it) }
            }
        }
    }

    /** Decodes the artwork, subsampling HD covers down to the card size
     *  instead of decoding them whole. Null when missing or unreadable. */
    private fun decodeCover(file: java.io.File?): Bitmap? {
        if (file == null || !file.exists()) return null
        return runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
            var sample = 1
            while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= SCREEN_BITMAP_SIZE) sample *= 2
            o.inJustDecodeBounds = false
            o.inSampleSize = sample
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
        }.getOrNull()
    }

    /** Average color of a rectangle of [src] (sampled on a coarse grid). */
    private fun averageColor(src: Bitmap, l: Int, t: Int, r: Int, b: Int): Int {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        val stepX = maxOf(1, (r - l) / 12); val stepY = maxOf(1, (b - t) / 12)
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = src.getPixel(x, y)
                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return roomAccent
        return Color.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    /** Pushes a sampled edge color towards a readable light: saturation at
     *  least 0.45 (keeps grey covers neutral-ish), lightness 0.50..0.62. */
    private fun asLightColor(c: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(c, hsl)
        if (hsl[1] > 0.08f) hsl[1] = maxOf(hsl[1], 0.45f)
        hsl[2] = hsl[2].coerceIn(0.50f, 0.62f)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    /** Backlight bitmap: 8 colors sampled from the artwork border (corners
     *  + side middles; room accent without artwork), blended by angle
     *  around the TV, with alpha fading out with the distance beyond the
     *  bezel. The area behind the bezel is fully lit (hidden anyway). */
    private fun buildBacklightBitmap(cover: Bitmap?, fallback: Int = roomAccent): Bitmap {
        val colors = IntArray(8)
        if (cover != null && !cover.isRecycled && cover.width >= 8 && cover.height >= 8) {
            val w = cover.width; val h = cover.height
            val bw = maxOf(1, w / 6); val bh = maxOf(1, h / 6)
            val third = w / 3; val thirdH = h / 3
            // Order by angle, starting at +X (right) going counter-clockwise
            // in screen terms (y up): right, top-right, top, top-left, left,
            // bottom-left, bottom, bottom-right.
            colors[0] = averageColor(cover, w - bw, thirdH, w, 2 * thirdH)
            colors[1] = averageColor(cover, w - bw, 0, w, bh)
            colors[2] = averageColor(cover, third, 0, 2 * third, bh)
            colors[3] = averageColor(cover, 0, 0, bw, bh)
            colors[4] = averageColor(cover, 0, thirdH, bw, 2 * thirdH)
            colors[5] = averageColor(cover, 0, h - bh, bw, h)
            colors[6] = averageColor(cover, third, h - bh, 2 * third, h)
            colors[7] = averageColor(cover, w - bw, h - bh, w, h)
        } else {
            colors.fill(fallback)
        }
        for (i in colors.indices) colors[i] = asLightColor(colors[i])

        val n = BACKLIGHT_BITMAP
        val half = n / 2f
        val tvHalf = (SCREEN_BEZEL_SIZE / tuning.backlightSize) * half   // bezel half-size in px
        val reach = half - tvHalf
        val corner = BACKLIGHT_CORNER * tvHalf
        val inner = tvHalf - corner
        val cr = FloatArray(8) { Color.red(colors[it]).toFloat() }
        val cg = FloatArray(8) { Color.green(colors[it]).toFloat() }
        val cb = FloatArray(8) { Color.blue(colors[it]).toFloat() }
        val anchorAng = FloatArray(8) { (it * Math.PI / 4).toFloat() }
        // Premultiplied channels, blurred afterwards.
        val pa = FloatArray(n * n); val pr = FloatArray(n * n)
        val pg = FloatArray(n * n); val pb = FloatArray(n * n)
        for (y in 0 until n) {
            val dy = half - (y + 0.5f)          // y up
            for (x in 0 until n) {
                val dx = (x + 0.5f) - half
                // Signed distance to a rounded square (the bezel): round
                // falloff contours instead of hard square corners.
                val qx = kotlin.math.abs(dx) - inner
                val qy = kotlin.math.abs(dy) - inner
                val out = kotlin.math.hypot(maxOf(qx, 0f), maxOf(qy, 0f)) +
                        minOf(maxOf(qx, qy), 0f) - corner
                val t = if (out <= 0f) 0f else out / reach
                if (t >= 1f) continue
                // Exponential falloff, eased to exactly zero at the edge.
                val a = tuning.backlightAlpha *
                        kotlin.math.exp(-t / tuning.backlightFalloff) * (1f - t) * (1f - t)
                // Smooth angular color: soft weights over the 8 edge samples
                // (no sector seams).
                val ang = kotlin.math.atan2(dy, dx)
                var wr = 0f; var wg = 0f; var wb = 0f; var ws = 0f
                for (k in 0 until 8) {
                    val w = kotlin.math.exp(2.2f * kotlin.math.cos(ang - anchorAng[k]))
                    wr += w * cr[k]; wg += w * cg[k]; wb += w * cb[k]; ws += w
                }
                val i = y * n + x
                pa[i] = a
                pr[i] = a * wr / ws; pg[i] = a * wg / ws; pb[i] = a * wb / ws
            }
        }
        val radius = tuning.backlightBlur
        if (radius > 0) {
            repeat(2) {
                for (ch in arrayOf(pa, pr, pg, pb)) boxBlur(ch, n, radius)
            }
        }
        val px = IntArray(n * n)
        for (i in px.indices) {
            val a = pa[i]
            if (a <= 0.002f) continue
            px[i] = Color.argb(
                (a * 255f).toInt().coerceIn(0, 255),
                (pr[i] / a).toInt().coerceIn(0, 255),
                (pg[i] / a).toInt().coerceIn(0, 255),
                (pb[i] / a).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Separable box blur of an [n]×[n] channel, edges clamped. */
    private fun boxBlur(ch: FloatArray, n: Int, radius: Int) {
        val tmp = FloatArray(n * n)
        val span = 2 * radius + 1
        for (y in 0 until n) {
            var sum = 0f
            for (k in -radius..radius) sum += ch[y * n + k.coerceIn(0, n - 1)]
            for (x in 0 until n) {
                tmp[y * n + x] = sum / span
                sum += ch[y * n + (x + radius + 1).coerceAtMost(n - 1)] -
                        ch[y * n + (x - radius).coerceAtLeast(0)]
            }
        }
        for (x in 0 until n) {
            var sum = 0f
            for (k in -radius..radius) sum += tmp[k.coerceIn(0, n - 1) * n + x]
            for (y in 0 until n) {
                ch[y * n + x] = sum / span
                sum += tmp[(y + radius + 1).coerceAtMost(n - 1) * n + x] -
                        tmp[(y - radius).coerceAtLeast(0) * n + x]
            }
        }
    }

    private fun buildPlaceholderCardBitmap(): Bitmap {
        return buildSongCardBitmap(null, "", "")
    }

    /** Composite a square card: cover centre-cropped to fill the square,
     *  a dark scrim rising from the bottom, and title + artist laid out at
     *  the bottom. Text wraps (title up to 2 lines, artist up to 2) and
     *  shrinks before it ever ellipsises, so long names stay readable. */
    private fun buildSongCardBitmap(cover: Bitmap?, title: String, artist: String): Bitmap {
        val size = SCREEN_BITMAP_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val filterPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        if (cover != null && !cover.isRecycled) {
            // Centre-crop to square.
            val side = minOf(cover.width, cover.height)
            val x = (cover.width - side) / 2
            val y = (cover.height - side) / 2
            c.drawBitmap(cover, android.graphics.Rect(x, y, x + side, y + side),
                android.graphics.Rect(0, 0, size, size), filterPaint)
        } else {
            c.drawPaint(Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(),
                    Color.rgb(34, 36, 42), Color.rgb(14, 15, 18), Shader.TileMode.CLAMP)
            })
        }
        if (title.isEmpty() && artist.isEmpty()) return bmp

        // Bottom scrim so the text reads over any artwork.
        c.drawRect(0f, size * 0.40f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(0f, size * 0.40f, 0f, size.toFloat(),
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(225, 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })

        val pad = 64
        val textWidth = size - pad * 2
        val baseFont = runCatching {
            androidx.core.content.res.ResourcesCompat.getFont(
                activity, com.jotapeveloper.fision.R.font.yugothic_ui_regular)
        }.getOrNull() ?: android.graphics.Typeface.DEFAULT
        val titlePaint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
            typeface = android.graphics.Typeface.create(baseFont, android.graphics.Typeface.BOLD)
            setShadowLayer(8f, 0f, 3f, Color.argb(200, 0, 0, 0))
        }
        val artistPaint = android.text.TextPaint().apply {
            isAntiAlias = true
            color = Color.argb(225, 255, 255, 255)
            typeface = baseFont
            setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
        }
        val artistLayout = fitTextLayout(artist, artistPaint, textWidth, 64f, 44f, 2)
        val titleLayout = fitTextLayout(title, titlePaint, textWidth, 104f, 68f, 2)

        // Stack from the bottom: artist last, title above it.
        var bottom = (size - pad).toFloat()
        if (artistLayout != null) {
            bottom -= artistLayout.height
            c.save(); c.translate(pad.toFloat(), bottom); artistLayout.draw(c); c.restore()
            bottom -= 18f
        }
        if (titleLayout != null) {
            bottom -= titleLayout.height
            c.save(); c.translate(pad.toFloat(), bottom); titleLayout.draw(c); c.restore()
        }
        return bmp
    }

    /** Wrapped layout of [text] at the largest size in [maxSize]..[minSize]
     *  that fits in [maxLines]; at the minimum size the last line
     *  ellipsises. Null for empty text. */
    private fun fitTextLayout(
        text: String, paint: android.text.TextPaint, width: Int,
        maxSize: Float, minSize: Float, maxLines: Int
    ): android.text.StaticLayout? {
        if (text.isEmpty()) return null
        fun build(sizePx: Float, ellipsize: Boolean): android.text.StaticLayout {
            paint.textSize = sizePx
            val b = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
            if (ellipsize) b.setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END)
            return b.build()
        }
        var s = maxSize
        while (s > minSize) {
            val l = build(s, false)
            if (l.lineCount <= maxLines) return l
            s -= 4f
        }
        return build(minSize, true)
    }

    fun pause() {
        if (!frameListenerAttached) return
        try { sceneView.onFrame = null } catch (_: Throwable) {}
        frameListenerAttached = false
    }

    fun resume() {
        ensureBillboardFrameListener()
    }

    fun release() {
        released = true
        bakeExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        pause()
        patternFields.clear()
        panelTexture = null
        trapTexture = null
        wallTexture = null
        ceilingTexture = null
        floorTexture = null
        ceilingMaterial = null
        clearSprites()
        wallNodes.forEach { runCatching { sceneView.removeChildNode(it) } }
        wallNodes.clear()
        // Acoustic panels are children of the wall planes, so they go with
        // the walls above. Bass traps hang off the scene root directly.
        trapEntries.forEach { runCatching { sceneView.removeChildNode(it.node) } }
        trapEntries.clear()
        floorNode?.let { runCatching { sceneView.removeChildNode(it) } }
        cornerLights.forEach { runCatching { sceneView.removeChildNode(it) } }
        cornerLights.clear()
        cornerBaseIntensity.clear()
        floorNode = null
        headNode = null
        headShadowNode?.let { runCatching { sceneView.removeChildNode(it) } }
        headShadowNode = null
        shadowBitmaps.forEach { it.recycle() }; shadowBitmaps = emptyList()
        headShadowBitmap?.recycle(); headShadowBitmap = null
        ringBitmap?.recycle();       ringBitmap = null
        // Song card: bezel + image are children of the back wall (freed
        // above); the composed bitmap is ours.
        screenBezel = null
        screenImage = null
        backlightNode = null
        backlightCover = null
        tiledPlanes.clear()
        roomPlanes = emptyList()
        panelMaterialInstance = null
        backWallRef = null
        screenBitmap?.let { runCatching { it.recycle() } }
        screenBitmap = null
        glassStreakBitmap?.let { runCatching { it.recycle() } }
        glassStreakBitmap = null
        // Material instances live on the shared MaterialLoader — they get
        // reused across song switches, so leave them to be freed by the
        // engine when the loader is destroyed by SceneView.
        roomMaterial = null
        floorMaterial = null
    }
}
