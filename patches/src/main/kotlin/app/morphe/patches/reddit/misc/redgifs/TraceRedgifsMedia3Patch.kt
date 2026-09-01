/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.redgifs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val TRACE_PROGRESSIVE_MEDIA_PERIOD_CLASS = "Landroidx/media3/exoplayer/source/b;"
private const val TRACE_PROGRESSIVE_LOADABLE_CLASS = "Ll150;"
private const val TRACE_DATA_SPEC_CLASS = "Lode;"
private const val TRACE_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedgifsMedia3Debug;"

private val TRACE_COMPATIBILITY_REDDIT_2026_34 = Compatibility(
    name = "Reddit",
    packageName = "com.reddit.frontpage",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0xFF4500,
    signatures = setOf(
        "970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f"
    ),
    targets = listOf(
        AppTarget(
            version = "2026.34.0",
            minSdk = 28,
            isExperimental = true,
        )
    ),
)

private fun traceParameterWidth(type: CharSequence): Int =
    when (type.toString()) {
        "J", "D" -> 2
        else -> 1
    }

private fun MutableMethod.traceInstanceRegister(): Int {
    require(!AccessFlags.STATIC.isSet(accessFlags)) { "Method $name has no instance register" }
    val implementation = implementation ?: error("Method $name has no implementation")
    val declaredWords = parameterTypes.sumOf { traceParameterWidth(it) }
    return implementation.registerCount - declaredWords - 1
}

private fun traceInvokeRange(
    methodName: String,
    parameterTypes: List<String>,
    returnType: String,
    startRegister: Int,
    registerCount: Int,
): BuilderInstruction = BuilderInstruction3rc(
    Opcode.INVOKE_STATIC_RANGE,
    startRegister,
    registerCount,
    ImmutableMethodReference(
        TRACE_EXTENSION_CLASS,
        methodName,
        parameterTypes,
        returnType,
    ),
)

private fun traceInvokeTwo(
    methodName: String,
    parameterTypes: List<String>,
    returnType: String,
    firstRegister: Int,
    secondRegister: Int,
): BuilderInstruction = BuilderInstruction35c(
    Opcode.INVOKE_STATIC,
    2,
    firstRegister,
    secondRegister,
    0,
    0,
    0,
    ImmutableMethodReference(
        TRACE_EXTENSION_CLASS,
        methodName,
        parameterTypes,
        returnType,
    ),
)

/**
 * Diagnostic-only patch. It never changes a URI, format, track, cache key, header, or playback
 * decision. It correlates the exact RedGIFs DataSpec with its owning ProgressiveMediaPeriod and
 * logs that period's SampleQueue/TrackGroup MIME types when ProgressiveMediaPeriod.z() returns.
 */
@Suppress("unused")
val traceRedgifsMedia3PeriodPatch = bytecodePatch(
    name = "Trace RedGIFs Media3 period",
    description = "Diagnostic-only: correlates RedGIFs DataSpecs with ProgressiveMediaPeriod track publication.",
    default = false,
) {
    compatibleWith(TRACE_COMPATIBILITY_REDDIT_2026_34)
    dependsOn(fixRedgifsFeedAudioPatch)

    execute {
        val loadableClass = mutableClassDefBy(TRACE_PROGRESSIVE_LOADABLE_CLASS)
        val outerPeriodFields = loadableClass.fields.filter { field ->
            field.type == TRACE_PROGRESSIVE_MEDIA_PERIOD_CLASS &&
                AccessFlags.SYNTHETIC.isSet(field.accessFlags) &&
                !AccessFlags.STATIC.isSet(field.accessFlags)
        }
        require(outerPeriodFields.size == 1) {
            "Expected exactly one ProgressiveMediaPeriod field on l150, found ${outerPeriodFields.size}"
        }
        val outerPeriodField = outerPeriodFields.single()

        val dataSpecBuilders = loadableClass.methods.filter { method ->
            method.name == "a" &&
                method.parameterTypes.map { it.toString() } == listOf("J", "Ljava/lang/String;") &&
                method.returnType == TRACE_DATA_SPEC_CLASS
        }
        require(dataSpecBuilders.size == 1) {
            "Expected exactly one l150 DataSpec builder, found ${dataSpecBuilders.size}"
        }
        val dataSpecBuilder = dataSpecBuilders.single()
        val loadableRegister = dataSpecBuilder.traceInstanceRegister()
        val dataSpecInstructions = dataSpecBuilder.implementation?.instructions?.toList()
            ?: error("l150 DataSpec builder has no implementation")

        // Reddit 2026.34 copies p0 into a low local at method entry. Reuse that dead local just
        // before return so the diagnostic hook does not alter the method's register layout.
        val loadableLocalRegister = dataSpecInstructions.firstNotNullOfOrNull { instruction ->
            if (
                instruction.opcode != Opcode.MOVE_OBJECT &&
                instruction.opcode != Opcode.MOVE_OBJECT_FROM16 &&
                instruction.opcode != Opcode.MOVE_OBJECT_16
            ) return@firstNotNullOfOrNull null
            val move = instruction as? TwoRegisterInstruction
                ?: return@firstNotNullOfOrNull null
            move.registerA.takeIf { move.registerB == loadableRegister && it <= 0xf }
        } ?: error("Could not find a low l150 instance alias for Media3 tracing")

        val dataSpecReturns = dataSpecInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.RETURN_OBJECT) return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA
                ?: error("Unexpected DataSpec return-object instruction")
            index to register
        }
        require(dataSpecReturns.isNotEmpty()) { "l150 DataSpec builder has no return-object sites" }

        dataSpecReturns.asReversed().forEach { (index, dataSpecRegister) ->
            require(dataSpecRegister <= 0xf) {
                "l150 DataSpec result register v$dataSpecRegister does not fit diagnostic invoke-static"
            }
            dataSpecBuilder.addInstructions(
                index,
                listOf(
                    BuilderInstruction22c(
                        Opcode.IGET_OBJECT,
                        loadableLocalRegister,
                        loadableLocalRegister,
                        ImmutableFieldReference(
                            TRACE_PROGRESSIVE_LOADABLE_CLASS,
                            outerPeriodField.name,
                            TRACE_PROGRESSIVE_MEDIA_PERIOD_CLASS,
                        ),
                    ),
                    traceInvokeTwo(
                        "onDataSpec",
                        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                        "V",
                        loadableLocalRegister,
                        dataSpecRegister,
                    ),
                ),
            )
        }

        val mediaPeriodClass = mutableClassDefBy(TRACE_PROGRESSIVE_MEDIA_PERIOD_CLASS)
        val publicationMethods = mediaPeriodClass.methods.filter { method ->
            method.name == "z" && method.parameterTypes.isEmpty() && method.returnType == "V"
        }
        require(publicationMethods.size == 1) {
            "Expected exactly one ProgressiveMediaPeriod.z() method, found ${publicationMethods.size}"
        }
        val publicationMethod = publicationMethods.single()
        val periodRegister = publicationMethod.traceInstanceRegister()
        val publicationInstructions = publicationMethod.implementation?.instructions?.toList()
            ?: error("ProgressiveMediaPeriod.z() has no implementation")
        val returnSites = publicationInstructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        require(returnSites.isNotEmpty()) { "ProgressiveMediaPeriod.z() has no return sites" }

        returnSites.asReversed().forEach { index ->
            publicationMethod.addInstructions(
                index,
                listOf(
                    traceInvokeRange(
                        "onPeriodState",
                        listOf("Ljava/lang/Object;"),
                        "V",
                        periodRegister,
                        1,
                    ),
                ),
            )
        }
    }
}
