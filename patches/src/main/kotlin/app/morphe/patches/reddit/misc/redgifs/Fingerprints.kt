/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.redgifs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CONTINUATION_IMPL_CLASS =
    "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
private const val URI_CLASS = "Landroid/net/Uri;"
private const val VP9_EXPANSION_VARIANT_CLASS =
    "Lcom/reddit/features/Vp9ExpansionVariant;"

/**
 * VideoProps is obfuscated, but its generated toString label and the public ExoKit parameter
 * types are stable semantic anchors. Keep the obfuscated class and PlaybackKey descriptors out
 * of the patch logic so ordinary R8 renaming does not break this hook.
 */
private object VideoPropsToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(
        string("VideoProps(url="),
    ),
)

internal object VideoPropsConstructorFingerprint : Fingerprint(
    classFingerprint = VideoPropsToStringFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    custom = { method, _ ->
        parametersMatch(
            method.parameters,
            listOf(
                "Ljava/lang/String;",
                "L",
                "L",
                "Lcom/reddit/exokit/api/ui/params/AutoplayType;",
                "Lcom/reddit/exokit/api/ui/params/MuteType;",
                "Lcom/reddit/exokit/api/ui/params/CaptionsType;",
                "Lcom/reddit/exokit/api/ui/params/StartPosition;",
                "Z",
                "L",
                "L",
            ),
        )
    },
)

/**
 * The media-source repository implementation and its helper types are obfuscated. Match the
 * coroutine method by stable data-flow landmarks instead: a VP9 feature gate and one requested
 * android.net.Uri read before that gate.
 */
internal object MediaSourceRepositoryProvideSourceFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        if (!parametersMatch(method.parameters, listOf("L", "L", CONTINUATION_IMPL_CLASS))) {
            false
        } else {
            val implementation = method.implementation
            if (implementation == null) {
                false
            } else {
                val instructions = implementation.instructions.toList()
                val validationIndices = instructions.mapIndexedNotNull { index, instruction ->
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            ?: return@mapIndexedNotNull null
                    val isStaticInvoke = instruction.opcode == Opcode.INVOKE_STATIC ||
                        instruction.opcode == Opcode.INVOKE_STATIC_RANGE
                    index.takeIf {
                        isStaticInvoke &&
                            reference.returnType == "Z" &&
                            reference.parameterTypes.map { it.toString() } ==
                            listOf(VP9_EXPANSION_VARIANT_CLASS)
                    }
                }
                if (validationIndices.size != 1) {
                    false
                } else {
                    val validationIndex = validationIndices.single()
                    val requestedUriReads = instructions.take(validationIndex).count { instruction ->
                        val reference =
                            (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        instruction.opcode == Opcode.IGET_OBJECT && reference?.type == URI_CLASS
                    }
                    requestedUriReads == 1
                }
            }
        }
    },
)

/**
 * Media3's progressive-loadable and DataSpec classes are both obfuscated. Identify the method
 * that constructs a DataSpec from a byte position and cache validator by protocol/data-flow
 * landmarks instead of its class, method, or return-type names.
 */
internal object ProgressiveDataSpecBuilderFingerprint : Fingerprint(
    filters = listOf(
        string("If-Range"),
    ),
    custom = { method, _ ->
        if (
            AccessFlags.STATIC.isSet(method.accessFlags) ||
            !parametersMatch(method.parameters, listOf("J", "Ljava/lang/String;")) ||
            !method.returnType.startsWith("L") ||
            method.returnType == "Ljava/lang/Object;"
        ) {
            false
        } else {
            val implementation = method.implementation
            if (implementation == null) {
                false
            } else {
                val instructions = implementation.instructions.toList()
                val uriReads = instructions.count { instruction ->
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? FieldReference
                    instruction.opcode == Opcode.IGET_OBJECT && reference?.type == URI_CLASS
                }
                val returnObjectCount = instructions.count { it.opcode == Opcode.RETURN_OBJECT }
                val resultAllocations = instructions.count { instruction ->
                    instruction.opcode == Opcode.NEW_INSTANCE &&
                        (instruction as? ReferenceInstruction)?.reference?.toString() ==
                        method.returnType
                }

                uriReads == 1 && returnObjectCount >= 1 && resultAllocations == 1
            }
        }
    },
)
