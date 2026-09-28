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
        // Default orbital pose. Behind the head (az=180°) with a slight
        // upward tilt so the room reads as "in front of the listener".
        // Angles captured from the live camera (scene info button log);
        // distance keeps the home pose inside the room (front wall at
        // z=-4.5, dollhouse toggle at 4.15) so it opens with the wall shown.
        private const val DEF_AZIMUTH_DEG = 179.7f
        private const val DEF_PITCH_DEG   = 19.7f
        private const val DEF_DISTANCE    = 4.2f

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
        // The centre 2-cols × 3-rows block of the back wall's panel grid
        // is skipped in addRoom to open the hole for it.
        // Wall centre is world Y=0.5, so local -0.4 → world 0.10 (a hair
        // below the head centre).
        private const val SCREEN_LOCAL_Y = -0.40f
        // Square image plus a uniform 0.06 bezel margin on every side.
        private const val SCREEN_IMG_SIZE = 1.88f
        private const val SCREEN_BEZEL_SIZE = 2.00f
        private const val SCREEN_BEZEL_D = 0.06f
        private const val SCREEN_BITMAP_SIZE = 1024

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
    /** Reflection streaks over the control-room window. */
    private var glassStreakBitmap: Bitmap? = null
    private var backWallRef: PlaneNode? = null
    private var pendingCover: Bitmap? = null
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
        warmWaiters.add(action)
        checkWarm()
    }

    fun cancelWarmWaiters() {
        warmWaiters.clear()
    }

    private fun checkWarm() {
        if (warmWaiters.isEmpty()) return
        if (framesRendered < WARM_MIN_FRAMES) return
        if (SystemClock.uptimeMillis() - setupAtMs < WARM_MIN_MS) return
        val pending = warmWaiters.toList()
        warmWaiters.clear()
        pending.forEach { it.run() }
    }

    fun setup() {
        setupAtMs = SystemClock.uptimeMillis()
        touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop
        doubleTapRadiusPx = (DOUBLE_TAP_RADIUS_DP * activity.resources.displayMetrics.density).toInt()
        prepareBitmaps()
        installTouchListener()
        addRoom()
        loadHead()
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
                addCornerLight(pos, LAMP_INTENSITY)
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
        ensureBillboardFrameListener()
    }

    fun stopDance() {
        danceActive = false
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

    /** The cubicle walls as six independent inward-facing planes (4 walls
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
    private fun addRoom() {
        try {
            val width  = 2f * ROOM_HALF_X
            val depth  = 2f * ROOM_HALF_Z
            val height = ROOM_CEIL_Y - ROOM_FLOOR_Y
            val centerY = (ROOM_FLOOR_Y + ROOM_CEIL_Y) / 2f

            // Walls carry a subtle tint of the active theme accent over the
            // matte gray base so each theme paints its own room.
            val accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(activity)
            // Wall tint: 35% accent over 65% neutral grey. Higher accent
            // shares (0.30 ratio → 70% accent) painted the room in
            // saturated colour that fought the dark acoustic panels and
            // competed with the sprites — the panels/sprites need the
            // wall to sit back.
            val wallTint  = androidx.core.graphics.ColorUtils.blendARGB(accent, Color.rgb(74, 76, 82), 0.65f)
            roomMaterial = sceneView.materialLoader.createColorInstance(
                wallTint, 0f, 0.9f, 0.02f
            )
            // Floor tone: slab + bottom plane. Same lit path as the walls so
            // the four ceiling lamps hit both surfaces consistently — the
            // earlier emissive shortcut auto-lit the floor and broke the
            // hierarchy (floor read brighter than the walls). Dark near-black
            // with a whisper of accent so the theme still tints the ground.
            // The gltfio ubershader defaults roughness to 0, which produced a
            // piano-black mirror; keep it high and matte instead.
            val floorTint = androidx.core.graphics.ColorUtils.blendARGB(Color.rgb(18, 18, 20), accent, 0.12f)
            floorMaterial = sceneView.materialLoader.createColorInstance(
                floorTint, 0f, 0.95f, 0.02f
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
                          Rotation(180f, 0f, 0f), Direction(0f, 1f, 0f), roomMaterial)
            )
            val created = mutableListOf<PlaneNode>()
            walls.forEach { spec ->
                val anchor = Node(sceneView.engine).apply { position = spec.center }
                val wall = PlaneNode(
                    engine = sceneView.engine,
                    size = spec.size,
                    center = Position(0f, 0f, 0f),
                    normal = spec.normal,
                    materialInstance = spec.material
                ).apply { rotation = spec.rotation }
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

            // Studio furniture. Panels read as studio foam: matte, darker
            // than the walls, no accent so they never compete with the
            // sprites. Each panel is a child of its wall plane — the wall's
            // local +Z points into the room, so the same local offsets work
            // for every wall, and the dollhouse visibility toggle (which
            // flips the wall node) hides the panels along with it.
            val panelMaterial = sceneView.materialLoader.createColorInstance(
                Color.rgb(40, 42, 46), 0f, 0.95f, 0.02f
            )
            // Side and back walls only: the front wall is hidden by the
            // dollhouse for most of the session, so its panels would be
            // wasted geometry.
            val backWall = created[1]
            backWallRef = backWall
            val panelWalls = listOf(backWall, created[2], created[3])
            val startX = -(PANEL_COLS - 1) * PANEL_COL_SPACING_X * 0.5f
            val startY = -(PANEL_ROWS - 1) * PANEL_ROW_SPACING_Y * 0.5f
            for (w in panelWalls) {
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
            // Front wall (behind the listener): control-room window + door.
            try {
                addStudioFrontWall(created[0])
            } catch (t: Throwable) {
                Log.e(TAG, "studio front wall failed", t)
            }
            // Bass traps: one vertical cylinder per corner, floor to
            // ceiling. Inset from the walls by their radius so they sit in
            // the corner without z-fighting either face. The (sx, sz)
            // signs identify the corner so updateRoomVisibility can hide
            // the trap when either adjacent wall goes.
            val trapMaterial = sceneView.materialLoader.createColorInstance(
                Color.rgb(30, 31, 34), 0f, 0.95f, 0.02f
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
            Log.w(TAG, "Room not added", t)
        }
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
        val accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(activity)
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
        val accent = com.jotapeveloper.fision.design.AccentColorManager.resolveAccent(activity)
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
                ibl.setIntensity(baseIndirectIntensity * k)
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
    fun animateLights(on: Boolean, durationMs: Long = 260L) {
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
        val cam = try { sceneView.cameraNode } catch (_: Throwable) { null } ?: return
        val cp = cam.position
        val dx = cp.x
        val dy = cp.y - HEAD_CENTER_Y
        val dz = cp.z
        val r = sqrt(dx * dx + dy * dy + dz * dz)
        if (r < 0.01f) return
        val az = atan2(dx.toDouble(), dz.toDouble()).toFloat()
        val pitch = asin((dy / r).coerceIn(-1f, 1f)).toFloat()
        Log.i(
            TAG,
            "CamPose az=%.1f° pitch=%.1f° dist=%.2f  world=(%.2f, %.2f, %.2f)"
                .format(Math.toDegrees(az.toDouble()), Math.toDegrees(pitch.toDouble()), r,
                    cp.x, cp.y, cp.z)
        )
    }

    // --- Song card --------------------------------------------------------

    /** Repaint the back-wall screen with the current song's card. Composed
     *  once here and left static — the image node never repaints on its
     *  own, so an idle scene costs nothing beyond the constant textured
     *  quad. Safe to call before setup(): the values are cached and applied
     *  when the screen node exists. */
    fun setSongCard(cover: Bitmap?, title: String, artist: String) {
        pendingCover = cover
        pendingTitle = title
        pendingArtist = artist
        val img = screenImage ?: return
        val bmp = buildSongCardBitmap(cover, title, artist)
        screenBitmap = bmp
        // ImageNode's bitmap setter re-uploads only mip level 0; the texture
        // is built with a full mip chain, so the lower levels (the ones
        // sampled at room distance) would keep showing the placeholder.
        // Regenerate the chain after the swap. The previous bitmap is not
        // recycled here: the upload is asynchronous and may still read it.
        try {
            img.bitmap = bmp
            img.texture.generateMipmaps(sceneView.engine)
        } catch (t: Throwable) {
            Log.w(TAG, "setSongCard bitmap swap failed", t)
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
        pause()
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
