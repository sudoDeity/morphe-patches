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
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

private const val GRAPHQL_MAPPER_CLASS =
    "Lcom/reddit/data/model/graphql/GqlDataToMediaDomainModelMapperKt;"
private const val GRAPHQL_MEDIA_FRAGMENT_CLASS = "Lsgt;"
private const val VIDEO_MEDIA_FRAGMENT_CLASS = "Lgim0;"
private const val ON_CELL_GROUP_FRAGMENT_CLASS = "Lety;"
private const val CELL_GROUP_FRAGMENT_CLASS = "Lxb7;"
private const val REDDIT_VIDEO_CLASS = "Lcom/reddit/domain/model/RedditVideo;"
private const val PLAYBACK_CONTROLLER_CLASS =
    "Lcom/reddit/exokit/internal/data/coordinator/a;"
private const val MEDIA_SOURCE_REPOSITORY_CLASS = "Lcom/reddit/mediacomponent/data/a;"
private const val MEDIA_ITEM_CLASS = "Lmit;"
private const val PLAYER_CACHE_CONTEXT_CLASS = "Lin00;"
private const val CONTINUATION_IMPL_CLASS = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
private const val PROGRESSIVE_LOADABLE_CLASS = "Ll150;"
private const val DATA_SPEC_CLASS = "Lode;"
private const val URI_CLASS = "Landroid/net/Uri;"
private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RedgifsPlaybackPatch;"

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
        require(cachedImpl.registerCount == 34) {
            "Unexpected cached Link -> video element mapper register count ${cachedImpl.registerCount}"
        }
        val cachedInstructions = cachedImpl.instructions.toList()
        fun cachedReference(index: Int): String? =
            (cachedInstructions[index] as? ReferenceInstruction)?.reference?.toString()
        val dashInvokeIndices = cachedInstructions.indices.filter { index ->
            cachedReference(index) == "$REDDIT_VIDEO_CLASS->getDashUrl()Ljava/lang/String;"
        }
        require(dashInvokeIndices.size == 1) {
            "Expected exactly one RedditVideo.getDashUrl call in cached Link mapper"
        }
        val dashInvokeIndex = dashInvokeIndices.single()
        require(dashInvokeIndex + 5 < cachedInstructions.size) {
            "Cached Link mapper ends unexpectedly after getDashUrl"
        }
        val dashResult = cachedInstructions[dashInvokeIndex + 1] as? OneRegisterInstruction
            ?: error("Unexpected getDashUrl result instruction")
        val widthResult = cachedInstructions[dashInvokeIndex + 3] as? OneRegisterInstruction
            ?: error("Unexpected getWidth result instruction")
        val heightResult = cachedInstructions[dashInvokeIndex + 5] as? OneRegisterInstruction
            ?: error("Unexpected getHeight result instruction")
        require(
            cachedInstructions[dashInvokeIndex].opcode == Opcode.INVOKE_VIRTUAL &&
                cachedInstructions[dashInvokeIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                dashResult.registerA == 13 &&
                cachedReference(dashInvokeIndex + 2) == "$REDDIT_VIDEO_CLASS->getWidth()I" &&
                cachedInstructions[dashInvokeIndex + 3].opcode == Opcode.MOVE_RESULT &&
                widthResult.registerA == 14 &&
                cachedReference(dashInvokeIndex + 4) == "$REDDIT_VIDEO_CLASS->getHeight()I" &&
                cachedInstructions[dashInvokeIndex + 5].opcode == Opcode.MOVE_RESULT &&
                heightResult.registerA == 15
        ) { "Unexpected cached Link mapper layout around RedditVideo.getDashUrl" }
        cachedLinkVideoMethod.addInstructions(
            dashInvokeIndex + 2,
            """
                invoke-virtual {v0}, Lcom/reddit/domain/model/Link;->getUrl()Ljava/lang/String;
                move-result-object v14
                move-object v15, v13
                invoke-static/range {v14 .. v15}, $EXTENSION_CLASS->registerCachedRedgifs(Ljava/lang/String;Ljava/lang/String;)V
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
        val gifGetterImplementation = gifGetter.implementation
            ?: error("RedditVideo.isGif has no implementation")
        val gifGetterInstructions = gifGetterImplementation.instructions.toList()
        val originalInstanceRegister = gifGetterImplementation.registerCount - 1
        require(
            gifGetterInstructions.size == 2 &&
                gifGetterInstructions[0].opcode == Opcode.IGET_BOOLEAN &&
                gifGetterInstructions[1].opcode == Opcode.RETURN
        ) { "Unsupported RedditVideo.isGif implementation for Reddit 2026.34" }
        val gifFieldRead = gifGetterInstructions[0] as? TwoRegisterInstruction
            ?: error("Unexpected RedditVideo.isGif field read")
        val gifReturn = gifGetterInstructions[1] as? OneRegisterInstruction
            ?: error("Unexpected RedditVideo.isGif return")
        val gifBackingField =
            (gifGetterInstructions[0] as? ReferenceInstruction)?.reference as? FieldReference
                ?: error("Could not resolve RedditVideo.isGif backing field")
        require(
            gifFieldRead.registerA == gifReturn.registerA &&
                gifFieldRead.registerB == originalInstanceRegister &&
                gifBackingField.definingClass == REDDIT_VIDEO_CLASS &&
                gifBackingField.type == "Z"
        ) { "Unexpected RedditVideo.isGif register or field layout" }

        val replacementGifGetter = ImmutableMethod(
            gifGetter.definingClass,
            gifGetter.name,
            emptyList(),
            gifGetter.returnType,
            gifGetter.accessFlags,
            gifGetter.annotations,
            gifGetter.hiddenApiRestrictions,
            MutableMethodImplementation(2),
        ).toMutable()
        replacementGifGetter.addInstructions(
            0,
            listOf(
                BuilderInstruction22c(Opcode.IGET_BOOLEAN, 0, 1, gifBackingField),
                invokeStaticRange(
                    "overrideIsGif",
                    listOf("Z", "Ljava/lang/Object;"),
                    "Z",
                    0,
                    2,
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                BuilderInstruction11x(Opcode.RETURN, 0),
            ),
        )
        require(redditVideoClass.methods.remove(gifGetter)) {
            "Could not remove original RedditVideo.isGif getter"
        }
        require(redditVideoClass.methods.add(replacementGifGetter)) {
            "Could not add replacement RedditVideo.isGif getter"
        }

        val playbackControllerClass = mutableClassDefBy(PLAYBACK_CONTROLLER_CLASS)
        val provideMediaSourceMethods = playbackControllerClass.methods.filter { method ->
            method.name == "d" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    MEDIA_SOURCE_REPOSITORY_CLASS,
                    "Ljava/lang/String;",
                    CONTINUATION_IMPL_CLASS,
                ) &&
                method.returnType == "Ljava/lang/Object;"
        }
        require(provideMediaSourceMethods.size == 1) {
            "Expected exactly one PlaybackController.provideMediaSource method, found ${provideMediaSourceMethods.size}"
        }
        val provideMediaSource = provideMediaSourceMethods.single()
        val playbackUrlRegister = provideMediaSource.declaredParameterRegister(1)
        provideMediaSource.addInstructions(
            0,
            listOf(
                invokeStaticRange(
                    "rewritePlaybackUrl",
                    listOf("Ljava/lang/String;"),
                    "Ljava/lang/String;",
                    playbackUrlRegister,
                    1,
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, playbackUrlRegister),
            ),
        )

        val repositoryClass = mutableClassDefBy(MEDIA_SOURCE_REPOSITORY_CLASS)
        val provideSourceMethods = repositoryClass.methods.filter { method ->
            method.name == "c" &&
                method.parameterTypes.map { it.toString() } == listOf(
                    MEDIA_ITEM_CLASS,
                    PLAYER_CACHE_CONTEXT_CLASS,
                    CONTINUATION_IMPL_CLASS,
                ) &&
                method.returnType == "Ljava/lang/Object;"
        }
        require(provideSourceMethods.size == 1) {
            "Expected exactly one RedditMediaSourceRepository.provideMediaSource method, found ${provideSourceMethods.size}"
        }
        val provideSource = provideSourceMethods.single()
        val repositoryInstructions = provideSource.implementation?.instructions?.toList()
            ?: error("RedditMediaSourceRepository.provideMediaSource has no implementation")

        val validationInvokes = repositoryInstructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@mapIndexedNotNull null
            if (
                instruction.opcode == Opcode.INVOKE_STATIC &&
                reference.toString() ==
                    "Lk9p0;->l0(Lcom/reddit/features/Vp9ExpansionVariant;)Z"
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
            if (
                instruction.opcode == Opcode.IGET_OBJECT &&
                reference.definingClass == "Ljit;" &&
                reference.name == "a" &&
                reference.type == URI_CLASS
            ) index else null
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

        val loadableClass = mutableClassDefBy(PROGRESSIVE_LOADABLE_CLASS)
        val dataSpecBuilders = loadableClass.methods.filter { method ->
            method.name == "a" &&
                method.parameterTypes.map { it.toString() } == listOf("J", "Ljava/lang/String;") &&
                method.returnType == DATA_SPEC_CLASS
        }
        require(dataSpecBuilders.size == 1) {
            "Expected exactly one l150 DataSpec builder, found ${dataSpecBuilders.size}"
        }
        val dataSpecBuilder = dataSpecBuilders.single()
        val dataSpecInstructions = dataSpecBuilder.implementation?.instructions?.toList()
            ?: error("l150 DataSpec builder has no implementation")
        val dataSpecReturns = dataSpecInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.RETURN_OBJECT) return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA
                ?: error("Unexpected DataSpec return-object instruction")
            index to register
        }
        require(dataSpecReturns.isNotEmpty()) { "l150 DataSpec builder has no return-object sites" }
        dataSpecReturns.asReversed().forEach { (index, dataSpecRegister) ->
            require(dataSpecRegister <= 0xff) {
                "l150 DataSpec result register v$dataSpecRegister cannot receive check-cast"
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
                        ImmutableTypeReference(DATA_SPEC_CLASS),
                    ),
                ),
            )
        }
    }
}
