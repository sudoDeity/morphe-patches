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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

private const val GRAPHQL_MAPPER_CLASS =
    "Lcom/reddit/data/model/graphql/GqlDataToMediaDomainModelMapperKt;"
private const val GRAPHQL_MEDIA_FRAGMENT_CLASS = "Lsgt;"
private const val LINK_DATA_MODEL_CLASS = "Lnmr;"
private const val REDDIT_VIDEO_CLASS = "Lcom/reddit/domain/model/RedditVideo;"
private const val VIDEO_PROPS_CLASS = "Lmkm0;"
private const val PLAYBACK_KEY_CLASS = "Lpm00;"
private const val PROGRESSIVE_MEDIA_PERIOD_CLASS = "Landroidx/media3/exoplayer/source/b;"
private const val PROGRESSIVE_LOADABLE_CLASS = "Ll150;"
private const val URI_CLASS = "Landroid/net/Uri;"
private const val DATA_SPEC_CLASS = "Lode;"
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
            isExperimental = true
        )
    )
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
    require(!AccessFlags.STATIC.isSet(accessFlags)) {
        "Method $name has no instance register"
    }
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
    description = "Uses live RedGIFs media in Reddit's native player and enables normal audio controls.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_REDDIT_2026_34)
    dependsOn(sharedExtensionPatch)
    extendWith("extensions/reddit.mpe")

    execute {
        val mapperClass = mutableClassDefBy(GRAPHQL_MAPPER_CLASS)
        val mapper = mapperClass.methods.singleOrNull { method ->
            method.name == "toRedditVideo" &&
                method.parameterTypes.map { it.toString() } ==
                listOf(GRAPHQL_MEDIA_FRAGMENT_CLASS) &&
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

        /*
         * Reddit can reconstruct a post entirely from its Room `link` cache without running
         * the GraphQL MediaFragment mapper. LinkDataModel (`nmr`) stores the complete
         * serialized Link in its third constructor argument (`linkJson`).
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
                method.parameterTypes.map { it.toString() } ==
                linkDataModelConstructorParameters &&
                method.returnType == "V"
        }

        require(linkDataModelConstructors.size == 1) {
            "Expected exactly one LinkDataModel constructor, found ${linkDataModelConstructors.size}"
        }

        val linkDataModelConstructor = linkDataModelConstructors.single()
        require(!AccessFlags.STATIC.isSet(linkDataModelConstructor.accessFlags)) {
            "Expected LinkDataModel constructor to be an instance method"
        }

        val linkDataModelInstructions =
            linkDataModelConstructor.implementation?.instructions?.toList()
                ?: error("LinkDataModel constructor has no implementation")
        val linkDataModelReturnSites = linkDataModelInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode == Opcode.RETURN_VOID) index else null
        }

        require(linkDataModelReturnSites.size == 1) {
            "Expected exactly one LinkDataModel constructor return, found ${linkDataModelReturnSites.size}"
        }

        val linkJsonRegister = linkDataModelConstructor.declaredParameterRegister(2)
        linkDataModelConstructor.addInstructions(
            linkDataModelReturnSites.single(),
            listOf(
                invokeStaticRange(
                    "registerCachedLinkJson",
                    listOf("Ljava/lang/String;"),
                    "V",
                    linkJsonRegister,
                    1,
                ),
            ),
        )

        val redditVideoClass = mutableClassDefBy(REDDIT_VIDEO_CLASS)
        val gifGetters = redditVideoClass.methods.filter { method ->
            method.name == "isGif" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == "Z"
        }

        require(gifGetters.size == 1) {
            "Expected exactly one RedditVideo.isGif getter, found ${gifGetters.size}"
        }

        val gifGetter = gifGetters.single()
        require(!AccessFlags.STATIC.isSet(gifGetter.accessFlags)) {
            "Expected RedditVideo.isGif to be an instance method"
        }

        val gifGetterImplementation = gifGetter.implementation
            ?: error("RedditVideo.isGif has no implementation")
        val gifGetterInstructions = gifGetterImplementation.instructions.toList()
        val originalInstanceRegister = gifGetterImplementation.registerCount - 1

        require(
            gifGetterInstructions.size == 2 &&
                gifGetterInstructions[0].opcode == Opcode.IGET_BOOLEAN &&
                gifGetterInstructions[1].opcode == Opcode.RETURN
        ) {
            "Unsupported RedditVideo.isGif implementation for Reddit 2026.34"
        }

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
        ) {
            "Unexpected RedditVideo.isGif register or field layout"
        }

        /*
         * Reddit 2026.34 uses a one-register Kotlin backing-field getter where p0 is both
         * `this` and the result register. Rebuild it with one local so Reddit's original
         * boolean is preserved and only registered RedGIFs transform true -> false.
         */
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
                BuilderInstruction22c(
                    Opcode.IGET_BOOLEAN,
                    0,
                    1,
                    gifBackingField,
                ),
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

        val videoPropsClass = mutableClassDefBy(VIDEO_PROPS_CLASS)
        val constructors = videoPropsClass.methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;" &&
                method.parameterTypes.getOrNull(1)?.toString() == PLAYBACK_KEY_CLASS
        }

        require(constructors.size == 1) {
            "Expected exactly one VideoProps(String, PlaybackKey, ...) constructor, found ${constructors.size}"
        }

        val constructor = constructors.single()
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

        /* Commit the route on the real ProgressiveMediaPeriod. Every l150 loadable
         * created for seeks, retries, or later loads receives the same final URI.
         */
        val mediaPeriodClass = mutableClassDefBy(PROGRESSIVE_MEDIA_PERIOD_CLASS)
        val mediaPeriodConstructors = mediaPeriodClass.methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.firstOrNull()?.toString() == URI_CLASS &&
                method.returnType == "V"
        }

        require(mediaPeriodConstructors.size == 1) {
            "Expected exactly one ProgressiveMediaPeriod constructor with a Uri, found " +
                mediaPeriodConstructors.size
        }

        val mediaPeriodConstructor = mediaPeriodConstructors.single()
        val mediaPeriodConstructorInstructions =
            mediaPeriodConstructor.implementation?.instructions?.toList()
                ?: error("ProgressiveMediaPeriod constructor has no implementation")
        val mediaPeriodSuperCallIndex = mediaPeriodConstructorInstructions.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_DIRECT &&
                instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                return@indexOfFirst false
            }

            val reference =
                (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@indexOfFirst false

            reference.name == "<init>" && reference.definingClass == mediaPeriodClass.superclass
        }

        require(mediaPeriodSuperCallIndex >= 0) {
            "Could not locate ProgressiveMediaPeriod superclass constructor call"
        }

        val mediaPeriodRegister = mediaPeriodConstructor.instanceRegister()
        val periodUriRegister = mediaPeriodConstructor.declaredParameterRegister(0)
        require(periodUriRegister == mediaPeriodRegister + 1) {
            "ProgressiveMediaPeriod instance and Uri registers are not contiguous"
        }
        require(periodUriRegister <= 0xff) {
            "ProgressiveMediaPeriod Uri register v$periodUriRegister cannot receive an object result"
        }

        mediaPeriodConstructor.addInstructions(
            mediaPeriodSuperCallIndex + 1,
            listOf(
                invokeStaticRange(
                    "resolvePeriodUri",
                    listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                    "Ljava/lang/Object;",
                    mediaPeriodRegister,
                    2,
                ),
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, periodUriRegister),
                BuilderInstruction21c(
                    Opcode.CHECK_CAST,
                    periodUriRegister,
                    ImmutableTypeReference(URI_CLASS),
                ),
            ),
        )

        /*
         * l150.a(long, String) constructs every DataSpec from the already-fixed period
         * URI. Pass its owning period so headers and cache identity come from the same
         * immutable PeriodRoute without a global URL lookup.
         */
        val loadableClass = mutableClassDefBy(PROGRESSIVE_LOADABLE_CLASS)
        val outerPeriodFields = loadableClass.fields.filter { field ->
            field.type == PROGRESSIVE_MEDIA_PERIOD_CLASS &&
                AccessFlags.SYNTHETIC.isSet(field.accessFlags) &&
                !AccessFlags.STATIC.isSet(field.accessFlags)
        }
        require(outerPeriodFields.size == 1) {
            "Expected exactly one ProgressiveMediaPeriod field on l150, found " +
                outerPeriodFields.size
        }
        val outerPeriodField = outerPeriodFields.single()

        val dataSpecBuilders = loadableClass.methods.filter { method ->
            method.name == "a" &&
                method.parameterTypes.map { it.toString() } ==
                listOf("J", "Ljava/lang/String;") &&
                method.returnType == DATA_SPEC_CLASS
        }

        require(dataSpecBuilders.size == 1) {
            "Expected exactly one l150 DataSpec builder, found " +
                dataSpecBuilders.size
        }

        val dataSpecBuilder = dataSpecBuilders.single()
        val loadableRegister = dataSpecBuilder.instanceRegister()
        val dataSpecBuilderInstructions = dataSpecBuilder.implementation?.instructions?.toList()
            ?: error("ProgressiveMediaPeriod DataSpec builder has no implementation")
        val loadableLocalRegister = dataSpecBuilderInstructions.firstNotNullOfOrNull { instruction ->
            if (instruction.opcode != Opcode.MOVE_OBJECT &&
                instruction.opcode != Opcode.MOVE_OBJECT_FROM16 &&
                instruction.opcode != Opcode.MOVE_OBJECT_16) {
                return@firstNotNullOfOrNull null
            }
            val move = instruction as? TwoRegisterInstruction
                ?: return@firstNotNullOfOrNull null
            move.registerA.takeIf { move.registerB == loadableRegister && it <= 0xf }
        } ?: error("Could not locate l150's low-register copy of this")
        val dataSpecReturnSites = dataSpecBuilderInstructions.mapIndexedNotNull { index, instruction ->
            if (instruction.opcode != Opcode.RETURN_OBJECT) return@mapIndexedNotNull null
            val register = (instruction as? OneRegisterInstruction)?.registerA
                ?: error("Unexpected return-object instruction in DataSpec builder")
            index to register
        }

        require(dataSpecReturnSites.isNotEmpty()) {
            "ProgressiveMediaPeriod DataSpec builder has no return-object sites"
        }

        dataSpecReturnSites.asReversed().forEach { (index, dataSpecRegister) ->
            val periodScratchRegister = (0..0xf).firstOrNull { register ->
                register != loadableLocalRegister && register != dataSpecRegister
            } ?: error("Could not reserve a low scratch register for l150 DataSpec hook")
            require(dataSpecRegister <= 0xf) {
                "l150 DataSpec result register does not fit invoke-static"
            }
            dataSpecBuilder.addInstructions(
                index,
                listOf(
                    BuilderInstruction22c(
                        Opcode.IGET_OBJECT,
                        periodScratchRegister,
                        loadableLocalRegister,
                        ImmutableFieldReference(
                            PROGRESSIVE_LOADABLE_CLASS,
                            outerPeriodField.name,
                            PROGRESSIVE_MEDIA_PERIOD_CLASS,
                        ),
                    ),
                    invokeStaticTwoRegisters(
                        "prepareDataSpec",
                        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
                        "Ljava/lang/Object;",
                        periodScratchRegister,
                        dataSpecRegister,
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
