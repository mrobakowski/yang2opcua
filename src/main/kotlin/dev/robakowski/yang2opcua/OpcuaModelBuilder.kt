package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.*
import org.opendaylight.yangtools.yang.model.api.*
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement
import org.opendaylight.yangtools.yang.model.api.stmt.*
import org.opendaylight.yangtools.yang.model.api.type.*
import org.opendaylight.yangtools.yang.parser.rfc7950.stmt.AbstractEffectiveModule
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import javax.xml.namespace.QName as JQName

const val OPC_UA_BASE_URI = "http://opcfoundation.org/UA/"

@Suppress("UnstableApiUsage", "MemberVisibilityCanBePrivate")
class OpcuaModelBuilder(yangModel: EffectiveModelContext, val outputFolder: String, designName: String) {
    private val rootNamespace = "urn:yang2opcua:$designName"
    private val opcuaRootModel = newModelDesign(designName)
    private val registeredTypes: MutableSet<JQName> = mutableSetOf()
    private val registeredIdentities: MutableSet<JQName> = mutableSetOf()
    private val namespaceMappings = mutableMapOf<URI, String>()

    fun build() = opcuaRootModel

    //    @Deprecated("This should probably be handled in the future.")
    val ignoredForNow = Unit

    init {
        yangModel.moduleStatements.forEach { (moduleName, module) ->
            module as AbstractEffectiveModule<*>
            println("processing module $moduleName")
            addYangModule(module)
        }
    }

    class RichModelDesign(val fileName: String) : ModelDesign()

    fun newModelDesign(designName: String): RichModelDesign {
        return RichModelDesign("$outputFolder/$designName.Model.xml").apply {
            targetNamespace = rootNamespace

            namespaces = NamespaceTable().apply {
                namespace += Namespace().apply {
                    name = "OpcUa"
                    version = "1.03"
                    publicationDate = "2013-12-02T00:00:00Z"
                    prefix = "Opc.Ua"
                    internalPrefix = "Opc.Ua.Server"
                    xmlNamespace = "http://opcfoundation.org/UA/2008/02/Types.xsd"
                    xmlPrefix = "OpcUa"
                    value = OPC_UA_BASE_URI
                }

                namespace += Namespace().apply {
                    name = "Yang2OpcUA_$designName"
                    version = "0.1"
                    publicationDate = Instant.now().toString()
                    prefix = designName
                    internalPrefix = designName
                    xmlPrefix = designName
                    xmlNamespace = targetNamespace
                    value = targetNamespace
                }
            }
        }
    }

    fun addYangModule(yangModule: ModuleEffectiveStatement) {
        yangModule.effectiveSubstatements().forEach {
            when (it) {
                is NamespaceEffectiveStatement -> ignoredForNow
                is PrefixEffectiveStatement -> ignoredForNow
                is ImportEffectiveStatement -> ignoredForNow
                is OrganizationEffectiveStatement -> ignoredForNow
                is ContactEffectiveStatement -> ignoredForNow
                is DescriptionEffectiveStatement -> ignoredForNow
                is RevisionEffectiveStatement -> ignoredForNow
                is FeatureEffectiveStatement -> ignoredForNow
                is ReferenceEffectiveStatement -> ignoredForNow
                is YangVersionEffectiveStatement -> ignoredForNow
                is IdentityEffectiveStatement -> addIdentityType(it)
                is ContainerEffectiveStatement -> addContainerObjectType(it)
                is TypedefEffectiveStatement -> addTypedef(it)

                // we process effective models, so this should already be inlined
                is GroupingEffectiveStatement -> ignoredForNow

                // we process effective models, so this should already be inlined
                is AugmentEffectiveStatement -> ignoredForNow
                else -> TODO("Effective Statement of type ${it.javaClass.simpleName} not supported")
            }
        }
    }

    fun safeText(s: String) = s
        .replace('\u2019', '\'')
        .replace("\n", " ")
        .replace("\t", " ")
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .filter { it < 128.toChar() }

    fun NodeDesign.copyDescription(documentedNode: DocumentedNode) {
        documentedNode.description?.orElse(null)?.let { desc ->
            description = LocalizedText().apply { value = safeText(desc) }
        }
    }

    fun NodeDesign.copyVisibleNames(schemaNode: SchemaNode) {
        browseName = schemaNode.qName.localName
        displayName = LocalizedText().apply { key = browseName; value = browseName }
    }

    fun Parameter.copyDescription(documentedNode: DocumentedNode) {
        documentedNode.description?.orElse(null)?.let { desc ->
            description = LocalizedText().apply { value = safeText(desc) }
        }
    }

    private var namespacePrefixCounter = 0
    private fun yangNamespacePrefix(n: URI): String =
        namespaceMappings[n] ?: "ns${namespacePrefixCounter++}__".also { namespaceMappings[n] = it }

    fun SchemaNode.getSymbolicName(suffix: String = ""): JQName {
        val potentiallyInvalidName = yangNamespacePrefix(qName.namespace) + path.pathFromRoot.joinToString("___") { it.localName } + suffix
        val iHopeThisIsValidCIdentifier = potentiallyInvalidName.replace("-", "__").replace(".", "_")

        iHopeThisIsValidCIdentifier.firstOrNull()?.let { if (!it.isJavaIdentifierStart()) error("invalid name: $iHopeThisIsValidCIdentifier") }
        if (!iHopeThisIsValidCIdentifier.drop(1).all { it.isJavaIdentifierPart() }) error("invalid name: $iHopeThisIsValidCIdentifier")

        return JQName(rootNamespace, iHopeThisIsValidCIdentifier)
    }

    fun getDataType(type: TypeDefinition<*>): JQName {
        if (type.isPrimitive()) {
            return translatePrimitiveType(type) ?: error("unknown primitive type [${type.javaClass.simpleName}]")
        }
        val symbolicName = type.getSymbolicName("DataType")
        if (symbolicName !in registeredTypes) registerDataType(type)
        return symbolicName
    }

    fun registerDataType(type: TypeDefinition<*>) {
        val sName = type.getSymbolicName("DataType")
        registeredTypes += sName

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += DataTypeDesign().apply {
            symbolicName = sName
            copyDescription(type)
            copyVisibleNames(type)

            val base = type.baseType
                ?: type // for weird cases where we get a kinda-primitive type here, like an enum from a leaf
            baseType = translatePrimitiveType(base)
                ?: error("typedef with base type [${base.javaClass.simpleName}] not supported")

            if (base is EnumTypeDefinition) {
                fields = ListOfFields().apply {
                    base.values.forEach {
                        field += Parameter().apply {
                            copyDescription(it)
                            name = it.name
                            identifier = it.value
                        }
                    }
                }
            }
        }
    }

    fun translatePrimitiveType(type: TypeDefinition<*>): JQName? {
        return when (type) {
            is StringTypeDefinition -> "String"
            is BinaryTypeDefinition -> "ByteString"
            is BitsTypeDefinition -> "UInt32" // TODO: bit flags?
            is BooleanTypeDefinition -> "Boolean"
            is DecimalTypeDefinition -> "Int64" // TODO: precision?
            is EmptyTypeDefinition -> "Structure"
            is EnumTypeDefinition -> "Enumeration"
            is IdentityrefTypeDefinition -> "NodeId" // TODO identityrefs should probably be implemented as references, not data types
            is IdentityTypeDefinition -> TODO("I think this is impossible?")
            is InstanceIdentifierTypeDefinition -> "ExpandedNodeId" // TODO: is this correct???

            is Int8TypeDefinition -> "SByte"
            is Int16TypeDefinition -> "Int16"
            is Int32TypeDefinition -> "Int32"
            is Int64TypeDefinition -> "Int64"

            is Uint8TypeDefinition -> "Byte"
            is Uint16TypeDefinition -> "UInt16"
            is Uint32TypeDefinition -> "UInt32"
            is Uint64TypeDefinition -> "UInt64"

            is LeafrefTypeDefinition -> "NodeId" // TODO: is this correct???
            is UnionTypeDefinition -> "Variant" // TODO: can we somehow further restrict this?

            else -> null
        }?.let(::opcuaRef)
    }

    fun opcuaRef(ref: String) = JQName(OPC_UA_BASE_URI, ref)

    /** Adds a primitive typedef */
    fun addTypedef(typedef: TypedefEffectiveStatement) {
        typedef as SchemaNode
        val symbolicName = typedef.getSymbolicName("DataType")
        // type might have already been registered by some leaf that needed it
        if (symbolicName !in registeredTypes) registerDataType(typedef.typeDefinition)
    }

    fun addIdentityType(identity: IdentityEffectiveStatement): JQName {
        identity as IdentitySchemaNode
        val sName = identity.getSymbolicName()

        if (sName in registeredIdentities) return sName
        registeredIdentities += sName

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(identity)
            copyVisibleNames(identity)

            if (identity.baseIdentities.isNotEmpty()) {
                val i = identity.baseIdentities
                    .singleOrNull().let { it ?: error("multiple inheritance is not supported [$identity]") }

                baseType = addIdentityType(i as IdentityEffectiveStatement) // to ensure proper ordering
            }
        }

        return sName
    }

    fun makeChildren(yangChildren: Collection<EffectiveStatement<*, *>>): ListOfChildren {
        return ListOfChildren().apply {
            yangChildren.forEach { child ->
                when (child) {
                    // region ignored
                    is ConfigEffectiveStatement -> ignoredForNow
                    is StatusEffectiveStatement -> ignoredForNow

                    // TODO: add feature processing (this should be probably implemented somewhere in opendaylight
                    //  already for effective statements)
                    is IfFeatureEffectiveStatement -> ignoredForNow

                    // we process effective models, so this should already be inlined
                    is UsesEffectiveStatement -> ignoredForNow

                    // TODO: are such schema conditionals supported in opcua?
                    is WhenEffectiveStatement -> ignoredForNow

                    // TODO: maybe add to the description?
                    is ReferenceEffectiveStatement -> ignoredForNow

                    // handled one level above
                    is DescriptionEffectiveStatement -> ignoredForNow

                    // TODO: are such constraints even supported in opcua?
                    is UniqueEffectiveStatement -> ignoredForNow

                    // we process effective models, so this should already be inlined
                    is AugmentEffectiveStatement -> ignoredForNow

                    // should be handled in ListEffectiveStatement below
                    is KeyEffectiveStatement -> ignoredForNow
                    // endregion
                    is ListEffectiveStatement -> {
                        // so, the mapping from yang lists goes like this
                        // 1) create an object type for the list object
                        // 2) create an object type for the list element
                        // 3) create a reference type that will represent that elements belong to the list
                        // 4) add the list object of type from 1) to the current type design
                        // TODO: validate if this is a correct encoding

                        child as ListSchemaNode
                        val listType = addListObjectType(child)
                        val elementType = addListElementObjectType(child)
                        val referenceType = addListElementReferenceType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child)
                            if (description != null) {
                                description.value += "\n\telement type: ${elementType.symbolicName}" +
                                        "\n\treference type: ${referenceType.symbolicName}" +
                                        "\n\telement key: ${child.keyDefinition.joinToString()}"
                            }
                            typeDefinition = listType.symbolicName
                        }
                    }
                    is LeafEffectiveStatement -> { // TODO: special-case this and model idnetityref leafs as references
                        child as LeafSchemaNode
                        val leafType = addLeafType(child)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child)
                            valueRank = ValueRank.SCALAR
                            typeDefinition = leafType.symbolicName
                        }
                    }
                    is LeafListEffectiveStatement -> {
                        child as LeafListSchemaNode
                        val leafListType = addLeafListType(child)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child)
                            valueRank = ValueRank.ARRAY
                            typeDefinition = leafListType.symbolicName
                        }
                    }
                    is ContainerEffectiveStatement -> {
                        child as ContainerSchemaNode
                        val containerType = addContainerObjectType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child)
                            typeDefinition = containerType.symbolicName
                        }
                    }
                    is ChoiceEffectiveStatement -> {
                        // I guess we can implement it as an abstract base class and a concrete class for each of the cases
                        child as ChoiceSchemaNode
                        val choiceType = addChoiceObjectType(child)
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child)
                            typeDefinition = choiceType.symbolicName
                        }
                    }
                    else -> TODO("container child of type [${child.javaClass.simpleName.ifEmpty { child.javaClass.name }}] not supported")
                }
            }
        }
    }

    fun addContainerObjectType(container: ContainerEffectiveStatement): ObjectTypeDesign {
        container as ContainerSchemaNode
        val sName = container.getSymbolicName("ContainerType")
        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(container)
            copyVisibleNames(container)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(container.effectiveSubstatements())
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListType")
        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list)
            baseType = opcuaRef("BaseObjectType")
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListElementType")
        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(list.effectiveSubstatements())
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addListElementReferenceType(list: ListEffectiveStatement): ReferenceTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListElementReferenceType")
        val res = ReferenceTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list)
            baseType = opcuaRef("HasComponent") // TODO: or maybe `Aggregates`?
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addLeafType(leaf: LeafEffectiveStatement): VariableTypeDesign {
        leaf as LeafSchemaNode
        val sName = leaf.getSymbolicName("LeafType")
        val res = VariableTypeDesign().apply {
            symbolicName = sName
            copyDescription(leaf)
            copyVisibleNames(leaf)
            valueRank = ValueRank.SCALAR
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leaf.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addLeafListType(leafList: LeafListEffectiveStatement): VariableTypeDesign {
        leafList as LeafListSchemaNode
        val sName = leafList.getSymbolicName("LeafListType")
        val res = VariableTypeDesign().apply {
            symbolicName = sName
            copyDescription(leafList)
            copyVisibleNames(leafList)
            valueRank = ValueRank.ARRAY
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leafList.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }

    fun addChoiceObjectType(choice: ChoiceEffectiveStatement): ObjectTypeDesign {
        choice as ChoiceSchemaNode
        val sName = choice.getSymbolicName("ChoiceType")
        val choiceType = ObjectTypeDesign().apply {
            symbolicName = sName
            isIsAbstract = true
            copyDescription(choice)
            copyVisibleNames(choice)
            baseType = opcuaRef("BaseObjectType")
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += choiceType
        choice.cases.forEach { (_, caseNode) -> addCaseObjectType(caseNode, choiceType.symbolicName) }

        return choiceType
    }

    fun addCaseObjectType(case: CaseSchemaNode, choiceType: JQName): ObjectTypeDesign {
        case as CaseEffectiveStatement
        val sName = case.getSymbolicName("CaseType")
        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(case)
            copyVisibleNames(case)
            baseType = choiceType

            children = makeChildren(case.effectiveSubstatements())
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        return res
    }
}

private fun TypeDefinition<*>.isPrimitive() =
    baseType == null &&
            // not very primitive types that yang considers primitive, but we don't want to
            this !is EnumTypeDefinition &&
            this !is UnionTypeDefinition &&
            this !is IdentityrefTypeDefinition &&
            this !is InstanceIdentifierTypeDefinition &&
            this !is LeafrefTypeDefinition