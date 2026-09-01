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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val GRAPHQL_MAPPER_CLASS =
    "Lcom/reddit/data/model/graphql/GqlDataToMediaDomainModelMapperKt;"
private const val GRAPHQL_MEDIA_FRAGMENT_CLASS = "Lsgt;"
private const val GRAPHQL_REDDIT_VIDEO_MEDIA_CLASS = "Lrgt;"
private const val VIDEO_MEDIA_FRAGMENT_CLASS = "Lgim0;"
private const val REDDIT_VIDEO_CLASS = "Lcom/reddit/domain/model/RedditVideo;"
private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch;"

private const val MEDIA_FRAGMENT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$MediaFragmentInterface;"
private const val REDDIT_VIDEO_MEDIA_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$RedditVideoMediaInterface;"
private const val VIDEO_MEDIA_FRAGMENT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$VideoMediaFragmentInterface;"
private const val REDDIT_VIDEO_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$RedditVideoInterface;"

private val COMPATIBILITY_REDDIT_2026_34 = Compatibility(
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

private fun parameterWidth(type: CharSequence): Int =
    when (type.toString()) {
        "J", "D" -> 2
        else -> 1
    }

private fun MutableMethod.declaredParameterRegister(index: Int): Int {
    require(index in parameterTypes.indices) {
        "Parameter index $index is outside $name(${parameterTypes.joinToString()})"
    }
    val implementation = implementation ?: error("Method $name has no implementation")
    val isStatic = AccessFlags.STATIC.isSet(accessFlags)
    val declaredParameterWords = parameterTypes.sumOf { parameterWidth(it) }
    val parameterAreaWords = declaredParameterWords + if (isStatic) 0 else 1
    val parameterAreaStart = implementation.registerCount - parameterAreaWords
    val precedingDeclaredWords = parameterTypes.take(index).sumOf { parameterWidth(it) }
    return parameterAreaStart + if (isStatic) precedingDeclaredWords else 1 + precedingDeclaredWords
}

private fun invokeStaticRange(
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
        EXTENSION_CLASS,
        methodName,
        parameterTypes,
        returnType,
    ),
)

@Suppress("unused")
val fixRedgifsFeedAudioPatch = bytecodePatch(
    name = "Fix RedGIFs feed audio",
    description = "Routes RedGIFs into Reddit's native playback source so native audio tracks are preserved.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_REDDIT_2026_34)
    dependsOn(sharedExtensionPatch)
    extendWith("extensions/reddit.mpe")

    execute {
        fun addBridgeAccessor(
            classType: String,
            interfaceType: String,
            methodName: String,
            fieldName: String,
            expectedFieldType: String,
            returnType: String,
        ) {
            val targetClass = mutableClassDefBy(classType)
            if (interfaceType !in targetClass.interfaces) targetClass.interfaces.add(interfaceType)
            require(targetClass.methods.none { method -> method.name == methodName }) {
                "Bridge method $classType->$methodName already exists"
            }
            val field = targetClass.fields.singleOrNull { candidate -> candidate.name == fieldName }
                ?: error("Expected exactly one $classType field named $fieldName")
            require(field.type == expectedFieldType) {
                "Unexpected $classType->$fieldName type ${field.type}; expected $expectedFieldType"
            }
            require(!AccessFlags.STATIC.isSet(field.accessFlags)) {
                "Bridge field $classType->$fieldName must be an instance field"
            }
            targetClass.methods.add(
                ImmutableMethod(
                    targetClass.type,
                    methodName,
                    emptyList(),
                    returnType,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $field
                            return-object v0
                        """.trimIndent(),
                    )
                }
            )
        }

        addBridgeAccessor(
            GRAPHQL_MEDIA_FRAGMENT_CLASS,
            MEDIA_FRAGMENT_INTERFACE,
            "patch_getRedditVideoMedia",
            "e",
            GRAPHQL_REDDIT_VIDEO_MEDIA_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            GRAPHQL_REDDIT_VIDEO_MEDIA_CLASS,
            REDDIT_VIDEO_MEDIA_INTERFACE,
            "patch_getVideoMedia",
            "b",
            VIDEO_MEDIA_FRAGMENT_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            VIDEO_MEDIA_FRAGMENT_CLASS,
            VIDEO_MEDIA_FRAGMENT_INTERFACE,
            "patch_getEmbedHtml",
            "a",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        )
        addBridgeAccessor(
            VIDEO_MEDIA_FRAGMENT_CLASS,
            VIDEO_MEDIA_FRAGMENT_INTERFACE,
            "patch_getUrl",
            "b",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        )

        val redditVideoClass = mutableClassDefBy(REDDIT_VIDEO_CLASS)
        listOf(
            "getPackagedMp4Url",
            "getDashUrl",
            "getFallBackUrl",
            "getHlsUrl",
        ).forEach { getterName ->
            require(redditVideoClass.methods.count { method ->
                method.name == getterName &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/lang/String;"
            } == 1) {
                "Expected exactly one RedditVideo.$getterName() String getter"
            }
        }
        if (REDDIT_VIDEO_INTERFACE !in redditVideoClass.interfaces) {
            redditVideoClass.interfaces.add(REDDIT_VIDEO_INTERFACE)
        }

        val mapperClass = mutableClassDefBy(GRAPHQL_MAPPER_CLASS)
        val mapper = mapperClass.methods.singleOrNull { method ->
            method.name == "toRedditVideo" &&
                method.parameterTypes.map { it.toString() } == listOf(GRAPHQL_MEDIA_FRAGMENT_CLASS) &&
                method.returnType == REDDIT_VIDEO_CLASS
        } ?: error("Could not find GraphQL MediaFragment to RedditVideo mapper")
        require(AccessFlags.STATIC.isSet(mapper.accessFlags)) {
            "Expected GraphQL MediaFragment to RedditVideo mapper to be static"
        }
        val mapperInstructions = mapper.implementation?.instructions?.toList()
            ?: error("GraphQL RedditVideo mapper has no implementation")
        val fragmentRegister = mapper.declaredParameterRegister(0)
        val returnSites = mapperInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.RETURN_OBJECT) return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA
                ?: error("Unexpected return-object instruction in RedditVideo mapper")
            index to register
        }
        require(returnSites.isNotEmpty()) {
            "GraphQL RedditVideo mapper has no return-object sites"
        }
        returnSites.asReversed().forEach { (index, resultRegister) ->
            mapper.addInstructions(
                index,
                listOf(
                    invokeStaticRange(
                        "captureMediaFragment",
                        listOf("Ljava/lang/Object;"),
                        "V",
                        fragmentRegister,
                        1,
                    ),
                    invokeStaticRange(
                        "registerRedditVideo",
                        listOf("Ljava/lang/Object;"),
                        "V",
                        resultRegister,
                        1,
                    ),
                ),
            )
        }

        val constructor = VideoPropsConstructorFingerprint.method
        val videoPropsClass = VideoPropsConstructorFingerprint.classDef
        require(constructor.definingClass == videoPropsClass.type) {
            "VideoProps fingerprint method/class mismatch"
        }
        val constructorInstructions = constructor.implementation?.instructions?.toList()
            ?: error("VideoProps constructor has no implementation")
        val superCallIndex = constructorInstructions.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_DIRECT &&
                instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                return@indexOfFirst false
            }
            val reference =
                (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@indexOfFirst false
            reference.name == "<init>" && reference.definingClass == videoPropsClass.superclass
        }
        require(superCallIndex >= 0) {
            "Could not locate VideoProps superclass constructor call"
        }

        val urlRegister = constructor.declaredParameterRegister(0)
        require(urlRegister <= 0xff) {
            "VideoProps URL parameter register v$urlRegister cannot receive an object result"
        }
        constructor.addInstructions(
            superCallIndex + 1,
            listOf(
                invokeStaticRange(
                    "rewritePlaybackUrl",
                    listOf("Ljava/lang/String;"),
                    "Ljava/lang/String;",
                    urlRegister,
                    1,
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, urlRegister),
            ),
        )
    }
}
