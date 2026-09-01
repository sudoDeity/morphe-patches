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
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

private const val GRAPHQL_MAPPER_CLASS =
    "Lcom/reddit/data/model/graphql/GqlDataToMediaDomainModelMapperKt;"
private const val GRAPHQL_MEDIA_FRAGMENT_CLASS = "Lsgt;"
private const val GRAPHQL_REDDIT_VIDEO_MEDIA_CLASS = "Lrgt;"
private const val VIDEO_MEDIA_FRAGMENT_CLASS = "Lgim0;"
private const val LINK_DATA_MODEL_CLASS = "Lnmr;"
private const val ON_CELL_GROUP_FRAGMENT_CLASS = "Lety;"
private const val CELL_GROUP_FRAGMENT_CLASS = "Lxb7;"
private const val CELL_CLASS = "Lvb7;"
private const val CELL_METADATA_CLASS = "Lh2u;"
private const val LEGACY_VIDEO_CLASS = "Lygr;"
private const val LEGACY_MEDIA_CLASS = "Lvgr;"
private const val LEGACY_MEDIA_SOURCE_CLASS = "Lnc7;"
private const val REDDIT_VIDEO_CLASS = "Lcom/reddit/domain/model/RedditVideo;"
private const val URI_CLASS = "Landroid/net/Uri;"
private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch;"

private const val MEDIA_FRAGMENT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$MediaFragmentInterface;"
private const val REDDIT_VIDEO_MEDIA_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$RedditVideoMediaInterface;"
private const val VIDEO_MEDIA_FRAGMENT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$VideoMediaFragmentInterface;"
private const val CELL_GROUP_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$CellGroupInterface;"
private const val CELL_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$CellInterface;"
private const val CELL_METADATA_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$CellMetadataInterface;"
private const val LEGACY_VIDEO_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$LegacyVideoInterface;"
private const val LEGACY_MEDIA_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$LegacyMediaInterface;"
private const val LEGACY_MEDIA_SOURCE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch$LegacyMediaSourceInterface;"
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

private fun MutableMethod.instanceRegister(): Int {
    require(!AccessFlags.STATIC.isSet(accessFlags)) { "Method $name has no instance register" }
    return declaredParameterRegister(0) - 1
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

private fun invokeStaticTwoRegisters(
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
        EXTENSION_CLASS,
        methodName,
        parameterTypes,
        returnType,
    ),
)

@Suppress("unused")
val fixRedgifsFeedAudioPatch = bytecodePatch(
    name = "Fix RedGIFs feed audio",
    description = "Routes RedGIFs into Reddit's native MediaSource before playback so native audio tracks and controls are preserved.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_REDDIT_2026_34)
    dependsOn(sharedExtensionPatch)
    extendWith("extensions/reddit.mpe")

    execute {
        /*
         * Keep Reddit/obfuscation ABI knowledge in the patcher. Runtime code only sees these
         * stable extension interfaces, whose bridge methods directly read the target fields.
         */
        fun addBridgeAccessor(
            classType: String,
            interfaceType: String,
            methodName: String,
            fieldName: String,
            expectedFieldType: String?,
            returnType: String,
        ) {
            val targetClass = mutableClassDefBy(classType)
            if (interfaceType !in targetClass.interfaces) targetClass.interfaces.add(interfaceType)
            require(targetClass.methods.none { method -> method.name == methodName }) {
                "Bridge method $classType->$methodName already exists"
            }
            val field = targetClass.fields.singleOrNull { candidate -> candidate.name == fieldName }
                ?: error("Expected exactly one $classType field named $fieldName")
            if (expectedFieldType != null) {
                require(field.type == expectedFieldType) {
                    "Unexpected $classType->$fieldName type ${field.type}; expected $expectedFieldType"
                }
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
        addBridgeAccessor(
            CELL_GROUP_FRAGMENT_CLASS,
            CELL_GROUP_INTERFACE,
            "patch_getCells",
            "c",
            null,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            CELL_CLASS,
            CELL_INTERFACE,
            "patch_getMetadata",
            "C",
            CELL_METADATA_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            CELL_CLASS,
            CELL_INTERFACE,
            "patch_getLegacyVideo",
            "z",
            LEGACY_VIDEO_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            CELL_METADATA_CLASS,
            CELL_METADATA_INTERFACE,
            "patch_getMediaPath",
            "l",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        )
        addBridgeAccessor(
            CELL_METADATA_CLASS,
            CELL_METADATA_INTERFACE,
            "patch_getMediaDomain",
            "m",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        )
        addBridgeAccessor(
            LEGACY_VIDEO_CLASS,
            LEGACY_VIDEO_INTERFACE,
            "patch_getMedia",
            "b",
            LEGACY_MEDIA_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            LEGACY_MEDIA_CLASS,
            LEGACY_MEDIA_INTERFACE,
            "patch_getMediaSource",
            "b",
            LEGACY_MEDIA_SOURCE_CLASS,
            "Ljava/lang/Object;",
        )
        addBridgeAccessor(
            LEGACY_MEDIA_SOURCE_CLASS,
            LEGACY_MEDIA_SOURCE_INTERFACE,
            "patch_getRedditPath",
            "a",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
        )

        val redditVideoAdapterClass = mutableClassDefBy(REDDIT_VIDEO_CLASS)
        listOf(
            "getPackagedMp4Url",
            "getDashUrl",
            "getFallBackUrl",
            "getHlsUrl",
        ).forEach { getterName ->
            require(redditVideoAdapterClass.methods.count { method ->
                method.name == getterName &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/lang/String;"
            } == 1) {
                "Expected exactly one RedditVideo.$getterName() String getter"
            }
        }
        if (REDDIT_VIDEO_INTERFACE !in redditVideoAdapterClass.interfaces) {
            redditVideoAdapterClass.interfaces.add(REDDIT_VIDEO_INTERFACE)
        }

        val videoMediaFragmentClass = mutableClassDefBy(VIDEO_MEDIA_FRAGMENT_CLASS)
        val videoMediaFragmentConstructors = videoMediaFragmentClass.methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Lfim0;",
                    "Leim0;",
                ) &&
                method.returnType == "V"
        }
        require(videoMediaFragmentConstructors.size == 1) {
            "Expected exactly one VideoMediaFragment constructor, found ${videoMediaFragmentConstructors.size}"
        }
        val videoMediaFragmentConstructor = videoMediaFragmentConstructors.single()
        val videoMediaFragmentInstructions =
            videoMediaFragmentConstructor.implementation?.instructions?.toList()
                ?: error("VideoMediaFragment constructor has no implementation")
        val videoMediaReturnSites = videoMediaFragmentInstructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        require(videoMediaReturnSites.size == 1) {
            "Expected exactly one VideoMediaFragment constructor return"
        }
        val embedHtmlRegister = videoMediaFragmentConstructor.declaredParameterRegister(0)
        val fragmentUrlRegister = videoMediaFragmentConstructor.declaredParameterRegister(1)
        require(fragmentUrlRegister == embedHtmlRegister + 1) {
            "VideoMediaFragment embedHtml/url registers are not contiguous"
        }
        videoMediaFragmentConstructor.addInstructions(
            videoMediaReturnSites.single(),
            listOf(
                invokeStaticRange(
                    "prewarmRedgifs",
                    listOf("Ljava/lang/String;", "Ljava/lang/String;"),
                    "V",
                    embedHtmlRegister,
                    2,
                ),
            ),
        )

        /*
         * Room can materialize cached links before GraphQL/CellGroup identity exists.
         * This hook never classifies a post. It only starts the resolver from the
         * structured top-level Link.url stored in linkJson, buying the cache path the
         * same head start as the network VideoMediaFragment constructor.
         */
        val linkDataModelClass = mutableClassDefBy(LINK_DATA_MODEL_CLASS)
        val linkDataModelConstructorParameters = listOf(
            "Ljava/lang/String;",
            "I",
            "Ljava/lang/String;",
            "J",
            "Ljava/lang/String;",
            "Ljava/lang/String;",
            "Z",
            "Ljava/lang/String;",
            "Z",
            "Z",
            "Z",
            "Ljava/lang/String;",
        )
        val linkDataModelConstructors = linkDataModelClass.methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map { it.toString() } == linkDataModelConstructorParameters &&
                method.returnType == "V"
        }
        require(linkDataModelConstructors.size == 1) {
            "Expected exactly one LinkDataModel constructor, found ${linkDataModelConstructors.size}"
        }
        val linkDataModelConstructor = linkDataModelConstructors.single()
        val linkDataModelInstructions = linkDataModelConstructor.implementation?.instructions?.toList()
            ?: error("LinkDataModel constructor has no implementation")
        val linkDataModelReturns = linkDataModelInstructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        require(linkDataModelReturns.size == 1) {
            "Expected exactly one LinkDataModel constructor return, found ${linkDataModelReturns.size}"
        }
        val linkJsonRegister = linkDataModelConstructor.declaredParameterRegister(2)
        linkDataModelConstructor.addInstructions(
            linkDataModelReturns.single(),
            listOf(
                invokeStaticRange(
                    "prewarmCachedLinkJson",
                    listOf("Ljava/lang/String;"),
                    "V",
                    linkJsonRegister,
                    1,
                ),
            ),
        )

        val onCellGroupClass = mutableClassDefBy(ON_CELL_GROUP_FRAGMENT_CLASS)
        val onCellGroupConstructors = onCellGroupClass.methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/util/List;",
                    CELL_GROUP_FRAGMENT_CLASS,
                ) &&
                method.returnType == "V"
        }
        require(onCellGroupConstructors.size == 1) {
            "Expected exactly one OnCellGroupFragment constructor, found ${onCellGroupConstructors.size}"
        }
        val onCellGroupConstructor = onCellGroupConstructors.single()
        val onCellGroupInstructions = onCellGroupConstructor.implementation?.instructions?.toList()
            ?: error("OnCellGroupFragment constructor has no implementation")
        val onCellGroupReturns = onCellGroupInstructions.mapIndexedNotNull { index, instruction ->
            index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
        }
        require(onCellGroupReturns.size == 1) {
            "Expected exactly one OnCellGroupFragment constructor return"
        }
        val cellGroupRegister = onCellGroupConstructor.declaredParameterRegister(4)
        onCellGroupConstructor.addInstructions(
            onCellGroupReturns.single(),
            listOf(
                invokeStaticRange(
                    "registerCellGroup",
                    listOf("Ljava/lang/Object;"),
                    "V",
                    cellGroupRegister,
                    1,
                ),
            ),
        )

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
        require(returnSites.isNotEmpty()) { "GraphQL RedditVideo mapper has no return-object sites" }
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

        data class CachedLinkVideoMethod(val classType: String, val methodName: String)
        val cachedLinkVideoCandidates = mutableListOf<CachedLinkVideoMethod>()
        classDefForEach { classDef ->
            classDef.methods.forEach methodLoop@{ method ->
                if (
                    method.parameterTypes.map { it.toString() } != listOf(
                        "Lcom/reddit/domain/model/Link;",
                        "I",
                        "Z",
                    ) ||
                    method.returnType != "Lmgm0;"
                ) return@methodLoop

                val instructions = method.implementation?.instructions ?: return@methodLoop
                val references = instructions.mapNotNull { instruction ->
                    (instruction as? ReferenceInstruction)?.reference?.toString()
                }
                if (
                    references.count {
                        it == "Lcom/reddit/domain/model/Link;->getUrl()Ljava/lang/String;"
                    } == 1 &&
                    references.count {
                        it == "Lcom/reddit/domain/model/Preview;->getRedditVideoPreview()" +
                            "Lcom/reddit/domain/model/RedditVideo;"
                    } == 1 &&
                    references.count {
                        it == "$REDDIT_VIDEO_CLASS->getDashUrl()Ljava/lang/String;"
                    } == 1
                ) {
                    cachedLinkVideoCandidates += CachedLinkVideoMethod(classDef.type, method.name)
                }
            }
        }
        require(cachedLinkVideoCandidates.size == 1) {
            "Expected exactly one cached Link -> video element mapper, found ${cachedLinkVideoCandidates.size}"
        }
        val cachedCandidate = cachedLinkVideoCandidates.single()
        val cachedLinkVideoClass = mutableClassDefBy(cachedCandidate.classType)
        val cachedLinkVideoMethod = cachedLinkVideoClass.methods.single { method ->
            method.name == cachedCandidate.methodName &&
                method.parameterTypes.map { it.toString() } == listOf(
                    "Lcom/reddit/domain/model/Link;", "I", "Z"
                ) &&
                method.returnType == "Lmgm0;"
        }
        val cachedImpl = cachedLinkVideoMethod.implementation
            ?: error("Cached Link -> video element mapper has no implementation")
        val cachedInstructions = cachedImpl.instructions.toList()
        fun cachedReference(index: Int): String? =
            (cachedInstructions[index] as? ReferenceInstruction)?.reference?.toString()
        fun invokeReceiverRegister(index: Int): Int {
            val instruction = cachedInstructions[index]
            return when (instruction) {
                is RegisterRangeInstruction -> instruction.startRegister
                is FiveRegisterInstruction -> instruction.registerC
                else -> error("Expected invoke instruction at cached Link mapper index $index")
            }
        }

        val dashInvokeIndices = cachedInstructions.indices.filter { index ->
            cachedReference(index) == "$REDDIT_VIDEO_CLASS->getDashUrl()Ljava/lang/String;"
        }
        require(dashInvokeIndices.size == 1) {
            "Expected exactly one RedditVideo.getDashUrl call in cached Link mapper"
        }
        val dashInvokeIndex = dashInvokeIndices.single()
        require(dashInvokeIndex + 6 < cachedInstructions.size) {
            "Cached Link mapper ends unexpectedly after getDashUrl"
        }
        val widthResult = cachedInstructions[dashInvokeIndex + 3] as? OneRegisterInstruction
            ?: error("Unexpected getWidth result instruction")
        val heightResult = cachedInstructions[dashInvokeIndex + 5] as? OneRegisterInstruction
            ?: error("Unexpected getHeight result instruction")
        val nextLinkUse =
            (cachedInstructions[dashInvokeIndex + 6] as? ReferenceInstruction)?.reference as? MethodReference
                ?: error("Expected Link method call after RedditVideo dimensions")
        val dashInvokeOpcode = cachedInstructions[dashInvokeIndex].opcode
        val nextLinkUseOpcode = cachedInstructions[dashInvokeIndex + 6].opcode
        require(
            (dashInvokeOpcode == Opcode.INVOKE_VIRTUAL ||
                dashInvokeOpcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                cachedInstructions[dashInvokeIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                cachedReference(dashInvokeIndex + 2) == "$REDDIT_VIDEO_CLASS->getWidth()I" &&
                cachedInstructions[dashInvokeIndex + 3].opcode == Opcode.MOVE_RESULT &&
                cachedReference(dashInvokeIndex + 4) == "$REDDIT_VIDEO_CLASS->getHeight()I" &&
                cachedInstructions[dashInvokeIndex + 5].opcode == Opcode.MOVE_RESULT &&
                heightResult.registerA == widthResult.registerA + 1 &&
                (nextLinkUseOpcode == Opcode.INVOKE_VIRTUAL ||
                    nextLinkUseOpcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                nextLinkUse.definingClass == "Lcom/reddit/domain/model/Link;"
        ) { "Unexpected cached Link mapper layout around RedditVideo.getDashUrl" }

        val redditVideoRegister = invokeReceiverRegister(dashInvokeIndex)
        val linkRegister = invokeReceiverRegister(dashInvokeIndex + 6)
        val linkUrlScratchRegister = widthResult.registerA
        val videoScratchRegister = heightResult.registerA
        cachedLinkVideoMethod.addInstructions(
            dashInvokeIndex + 2,
            """
                invoke-virtual/range {v$linkRegister .. v$linkRegister}, Lcom/reddit/domain/model/Link;->getUrl()Ljava/lang/String;
                move-result-object v$linkUrlScratchRegister
                move-object/from16 v$videoScratchRegister, v$redditVideoRegister
                invoke-static/range {v$linkUrlScratchRegister .. v$videoScratchRegister}, $EXTENSION_CLASS->registerCachedRedgifs(Ljava/lang/String;Ljava/lang/Object;)V
            """.trimIndent(),
        )

        val redditVideoClass = mutableClassDefBy(REDDIT_VIDEO_CLASS)
        val gifGetters = redditVideoClass.methods.filter { method ->
            method.name == "isGif" && method.parameterTypes.isEmpty() && method.returnType == "Z"
        }
        require(gifGetters.size == 1) {
            "Expected exactly one RedditVideo.isGif getter, found ${gifGetters.size}"
        }
        val gifGetter = gifGetters.single()
        require(!AccessFlags.STATIC.isSet(gifGetter.accessFlags)) {
            "RedditVideo.isGif must be an instance method"
        }
        require(gifGetter.implementation != null) {
            "RedditVideo.isGif has no implementation"
        }
        val originalGifHelperName = "patch_originalIsGif"
        require(redditVideoClass.methods.none { method ->
            method.name == originalGifHelperName &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "Z"
        }) {
            "RedditVideo.$originalGifHelperName already exists"
        }
        val originalGifHelper = gifGetter.cloneMutable(name = originalGifHelperName)
        require(redditVideoClass.methods.add(originalGifHelper)) {
            "Could not add cloned RedditVideo.isGif helper"
        }

        val replacementGifGetter = ImmutableMethod(
            gifGetter.definingClass,
            gifGetter.name,
            emptyList(),
            gifGetter.returnType,
            gifGetter.accessFlags,
            gifGetter.annotations,
            gifGetter.hiddenApiRestrictions,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-virtual {p0}, $originalGifHelper
                    move-result v0
                    invoke-static {v0, p0}, $EXTENSION_CLASS->overrideIsGif(ZLjava/lang/Object;)Z
                    move-result v0
                    return v0
                """.trimIndent(),
            )
        }
        require(redditVideoClass.methods.remove(gifGetter)) {
            "Could not remove original RedditVideo.isGif getter"
        }
        require(redditVideoClass.methods.add(replacementGifGetter)) {
            "Could not add wrapped RedditVideo.isGif getter"
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

        val provideSource = MediaSourceRepositoryProvideSourceFingerprint.method
        val repositoryInstructions = provideSource.implementation?.instructions?.toList()
            ?: error("RedditMediaSourceRepository.provideMediaSource has no implementation")

        val validationInvokes = repositoryInstructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@mapIndexedNotNull null
            val isStaticInvoke = instruction.opcode == Opcode.INVOKE_STATIC ||
                instruction.opcode == Opcode.INVOKE_STATIC_RANGE
            if (
                isStaticInvoke &&
                reference.returnType == "Z" &&
                reference.parameterTypes.map { it.toString() } ==
                listOf("Lcom/reddit/features/Vp9ExpansionVariant;")
            ) index else null
        }
        require(validationInvokes.size == 1) {
            "Expected exactly one VP9 cache URI validation gate, found ${validationInvokes.size}"
        }
        val validationInvokeIndex = validationInvokes.single()

        val requestedUriReads = repositoryInstructions.mapIndexedNotNull { index, instruction ->
            if (index >= validationInvokeIndex) return@mapIndexedNotNull null
            val reference = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                ?: return@mapIndexedNotNull null
            if (instruction.opcode == Opcode.IGET_OBJECT && reference.type == URI_CLASS) index else null
        }
        require(requestedUriReads.size == 1) {
            "Expected exactly one requested MediaItem URI read before the cache gate, found ${requestedUriReads.size}"
        }
        val requestedUriInstruction =
            repositoryInstructions[requestedUriReads.single()] as? TwoRegisterInstruction
                ?: error("Unexpected requested URI field read")
        val requestedUriRegister = requestedUriInstruction.registerA

        val validationMoveIndex = validationInvokeIndex + 1
        require(validationMoveIndex < repositoryInstructions.size &&
            repositoryInstructions[validationMoveIndex].opcode == Opcode.MOVE_RESULT) {
            "Unexpected instruction after VP9 cache URI validation gate"
        }
        val validationRegister =
            (repositoryInstructions[validationMoveIndex] as? OneRegisterInstruction)?.registerA
                ?: error("Could not read cache validation result register")
        require(validationRegister <= 0xf && requestedUriRegister <= 0xf) {
            "Cache validation hook registers do not fit invoke-static"
        }
        provideSource.addInstructions(
            validationMoveIndex + 1,
            listOf(
                invokeStaticTwoRegisters(
                    "forceCacheUriValidation",
                    listOf("Z", "Ljava/lang/Object;"),
                    "Z",
                    validationRegister,
                    requestedUriRegister,
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT, validationRegister),
            ),
        )

        val dataSpecBuilder = ProgressiveDataSpecBuilderFingerprint.method
        val dataSpecType = dataSpecBuilder.returnType
        val dataSpecInstructions = dataSpecBuilder.implementation?.instructions?.toList()
            ?: error("Progressive DataSpec builder has no implementation")
        val dataSpecReturns = dataSpecInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.RETURN_OBJECT) return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA
                ?: error("Unexpected DataSpec return-object instruction")
            index to register
        }
        require(dataSpecReturns.isNotEmpty()) {
            "Progressive DataSpec builder has no return-object sites"
        }
        dataSpecReturns.asReversed().forEach { (index, dataSpecRegister) ->
            require(dataSpecRegister <= 0xff) {
                "DataSpec result register v$dataSpecRegister cannot receive check-cast"
            }
            dataSpecBuilder.addInstructions(
                index,
                listOf(
                    invokeStaticRange(
                        "prepareDataSpec",
                        listOf("Ljava/lang/Object;"),
                        "Ljava/lang/Object;",
                        dataSpecRegister,
                        1,
                    ),
                    BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, dataSpecRegister),
                    BuilderInstruction21c(
                        Opcode.CHECK_CAST,
                        dataSpecRegister,
                        ImmutableTypeReference(dataSpecType),
                    ),
                ),
            )
        }
    }
}
