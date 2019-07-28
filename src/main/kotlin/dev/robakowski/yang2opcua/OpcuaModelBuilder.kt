package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.*
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier
import org.opendaylight.yangtools.yang.data.api.schema.*
import org.opendaylight.yangtools.yang.model.api.*
import org.opendaylight.yangtools.yang.model.api.meta.EffectiveStatement
import org.opendaylight.yangtools.yang.model.api.stmt.*
import org.opendaylight.yangtools.yang.model.api.type.*
import org.opendaylight.yangtools.yang.parser.rfc7950.stmt.AbstractEffectiveModule
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import javax.xml.bind.JAXBElement
import javax.xml.namespace.QName as JQName

const val OPC_UA_BASE_URI = "http://opcfoundation.org/UA/"

@Suppress("UnstableApiUsage", "MemberVisibilityCanBePrivate")
class OpcuaModelBuilder(
    val yangModel: EffectiveModelContext,
    designName: String,
    val rootNode: NormalizedNode<*, *>?
) {
    private val rootNamespace = "urn:yang2opcua:$designName"
    private val opcuaRootModel = newModelDesign(designName)
    private val registeredTypes: MutableSet<JQName> = mutableSetOf()
    private val registeredIdentities: MutableSet<JQName> = mutableSetOf()
    private val namespaceMappings = mutableMapOf<URI, String>()

    fun build(): ModelDesign {
        yangModel.moduleStatements.forEach { (moduleName, module) ->
            module as AbstractEffectiveModule<*>
            println("processing module $moduleName")
            addYangModule(module, rootNode)
        }

        return opcuaRootModel
    }

    //    @Deprecated("This should probably be handled in the future.")
    val ignoredForNow = Unit
    
    fun newModelDesign(designName: String): ModelDesign {
        return ModelDesign().apply {
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

    fun addYangModule(
        yangModule: ModuleEffectiveStatement,
        rootNode: NormalizedNode<*, *>?
    ) {
        yangModule.effectiveSubstatements().forEach { es ->
            when (es) {
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
                is IdentityEffectiveStatement -> addIdentityType(es)
                is ContainerEffectiveStatement -> {
                    val thisNode = (rootNode as? ContainerNode)?.takeIf { es.identifier == it.identifier.nodeType }
                    addContainerObjectType(es, thisNode)
                }
                is TypedefEffectiveStatement -> addTypedef(es)

                // we process effective models, so this should already be inlined
                is GroupingEffectiveStatement -> ignoredForNow

                // we process effective models, so this should already be inlined
                is AugmentEffectiveStatement -> ignoredForNow
                else -> TODO("Effective Statement of type ${es.javaClass.simpleName} not supported")
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

    fun NodeDesign.copyVisibleNames(schemaNode: SchemaNode, parent: ObjectDesign? = null, prefix: String = "", suffix: String = "") {
        browseName = schemaNode.qName.localName + suffix
        if (!prefix.isBlank()) {
            browseName = prefix + browseName.capitalize()
        }

        val dispName = parent?.browseName?.let { "$it/$browseName" } ?: browseName

        displayName = LocalizedText().apply { key = dispName; value = dispName }
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
        val potentiallyInvalidName =
            yangNamespacePrefix(qName.namespace) + path.pathFromRoot.joinToString("___") { it.localName } + suffix
        val iHopeThisIsValidCIdentifier = potentiallyInvalidName.replace("-", "__").replace(".", "_")

        iHopeThisIsValidCIdentifier.firstOrNull()
            ?.let { if (!it.isJavaIdentifierStart()) error("invalid name: $iHopeThisIsValidCIdentifier") }
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

    fun makeChildren(
        yangChildren: Collection<EffectiveStatement<*, *>>,
        yangChildrenData: Collection<DataContainerChild<out YangInstanceIdentifier.PathArgument, *>>?,
        parent: ObjectDesign?
    ): ListOfChildren {
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

                        val childData = yangChildrenData?.find { childData ->
                            (childData as? MapNode)
                                ?.let { it.identifier.nodeType == child.identifier }
                                ?: false
                        } as? MapNode

                        child as ListSchemaNode
                        val listType = addListObjectType(child)
                        val elementType = addListElementObjectType(child)
                        val referenceType = addListElementReferenceType(child)
                        val sName = child.getSymbolicName()
                        objectOrVariableOrProperty += ObjectDesign().apply {
                            symbolicName = sName
                            copyDescription(child)
                            copyVisibleNames(child, suffix = "List")
                            if (description != null) {
                                description.value += "\n\telement type: ${elementType.symbolicName}" +
                                        "\n\treference type: ${referenceType.symbolicName}" +
                                        "\n\telement key: ${child.keyDefinition.joinToString()}"
                            }
                            typeDefinition = listType.symbolicName
                        }

                        if (childData != null && parent != null) {
                            addListElementObjectInstances(child, childData, elementType, referenceType, parent)
                        }
                    }
                    is LeafEffectiveStatement -> { // TODO: special-case this and model idnetityref leafs as references
                        val childData = yangChildrenData?.find { childData ->
                            (childData as? LeafNode)
                                ?.let { it.identifier.nodeType == child.identifier }
                                ?: false
                        } as? LeafNode

                        child as LeafSchemaNode
                        val leafType = addLeafType(child)

                        objectOrVariableOrProperty += VariableDesign().apply {
                            symbolicName = child.getSymbolicName()
                            copyDescription(child)
                            copyVisibleNames(child, parent = parent)
                            valueRank = ValueRank.SCALAR
                            typeDefinition = leafType.symbolicName
                            accessLevel = AccessLevel.READ_WRITE

                            if (childData != null) {
                                defaultValue = packPrimitive(childData.value, leafType.dataType)
                            }
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
                        val containerType = addContainerObjectType(child, null)
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

    private fun packPrimitive(value: Any?, expectedType: JQName): DefaultValue {
        return DefaultValue().apply {
            any = JAXBElement(JQName("http://opcfoundation.org/UA/2008/02/Types.xsd", expectedType.localPart), value?.javaClass, value)
        }
    }

    private val elementInstanceCounter: MutableMap<JQName, Int> = mutableMapOf()
    private fun addListElementObjectInstances(
        list: ListSchemaNode,
        listData: MapNode,
        listElementType: ObjectTypeDesign,
        listReferenceType: ReferenceTypeDesign,
        listParent: ObjectDesign
    ) {
        val listName = list.getSymbolicName()
        val listFullName = JQName(listName.namespaceURI, listParent.symbolicName.localPart + "_" + listName.localPart)

        listData.value.forEach { mapEntry ->
            val i = elementInstanceCounter.getOrDefault(listName, 0)
            elementInstanceCounter[listName] = i + 1

            val listElement = ObjectDesign().apply {
                typeDefinition = listElementType.symbolicName
                symbolicName = list.getSymbolicName("ElementInstance$i")
                copyDescription(list)
                copyVisibleNames(list, suffix = " $i")

                references = ListOfReferences().apply {
                    reference += Reference().apply {
                        referenceType = listReferenceType.symbolicName
                        isIsInverse = true
                        targetId = listFullName
                    }
                }

                children = makeChildren((list as ListEffectiveStatement).effectiveSubstatements(), mapEntry.value, this)
            }

            opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += listElement
        }
    }

    private val registeredContainerObjectTypes: MutableMap<JQName, ObjectTypeDesign> = mutableMapOf()
    fun addContainerObjectType(
        container: ContainerEffectiveStatement,
        thisNode: ContainerNode?
    ): ObjectTypeDesign {
        container as ContainerSchemaNode
        val sName = container.getSymbolicName("ContainerType")
        registeredContainerObjectTypes[sName]?.let { return it }

        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(container)
            copyVisibleNames(container)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(container.effectiveSubstatements(), null, null)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredContainerObjectTypes += sName to res

        if (thisNode != null) {
            val objectDesign = ObjectDesign().apply {
                symbolicName = container.getSymbolicName("ContainerInstance")
                copyDescription(container)
                copyVisibleNames(container)
                typeDefinition = sName

                references = ListOfReferences().apply {
                    reference += Reference().apply {
                        referenceType = opcuaRef("Organizes")
                        targetId = opcuaRef("ObjectsFolder")
                        isIsInverse = true
                    }
                }

                children = makeChildren(container.effectiveSubstatements(), thisNode.value, this)
            }

            opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += objectDesign
        }

        return res
    }

    val registeredListObjectTypes: MutableMap<JQName, ObjectTypeDesign> = mutableMapOf()
    fun addListObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListType")
        registeredListObjectTypes[sName]?.let { return it }

        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list)
            baseType = opcuaRef("BaseObjectType")
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredListObjectTypes += sName to res
        return res
    }

    val registeredListElementObjectTypes: MutableMap<JQName, ObjectTypeDesign> = mutableMapOf()
    fun addListElementObjectType(list: ListEffectiveStatement): ObjectTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListElementType")
        registeredListElementObjectTypes[sName]?.let { return it }

        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list)
            baseType = opcuaRef("BaseObjectType")

            children = makeChildren(list.effectiveSubstatements(), null, null)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredListElementObjectTypes += sName to res
        return res
    }

    val registeredListElementReferenceTypes: MutableMap<JQName, ReferenceTypeDesign> = mutableMapOf()
    fun addListElementReferenceType(list: ListEffectiveStatement): ReferenceTypeDesign {
        list as ListSchemaNode
        val sName = list.getSymbolicName("ListElementReferenceType")
        registeredListElementReferenceTypes[sName]?.let { return it }

        val res = ReferenceTypeDesign().apply {
            symbolicName = sName
            copyDescription(list)
            copyVisibleNames(list, prefix = "Contains")
            baseType = opcuaRef("HasComponent") // TODO: or maybe `Aggregates`?
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredListElementReferenceTypes += sName to res
        return res
    }

    private val registeredLeafTypes: MutableMap<JQName, VariableTypeDesign> = mutableMapOf()
    fun addLeafType(leaf: LeafEffectiveStatement): VariableTypeDesign {
        leaf as LeafSchemaNode
        val sName = leaf.getSymbolicName("LeafType")
        registeredLeafTypes[sName]?.let { return it }

        val res = VariableTypeDesign().apply {
            symbolicName = sName
            copyDescription(leaf)
            copyVisibleNames(leaf)
            valueRank = ValueRank.SCALAR
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leaf.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredLeafTypes += sName to res
        return res
    }

    private val registeredLeafListTypes: MutableMap<JQName, VariableTypeDesign> = mutableMapOf()
    fun addLeafListType(leafList: LeafListEffectiveStatement): VariableTypeDesign {
        leafList as LeafListSchemaNode
        val sName = leafList.getSymbolicName("LeafListType")
        registeredLeafListTypes[sName]?.let { return it }

        val res = VariableTypeDesign().apply {
            symbolicName = sName
            copyDescription(leafList)
            copyVisibleNames(leafList)
            valueRank = ValueRank.ARRAY
            baseType = opcuaRef("BaseDataVariableType")
            dataType = getDataType(leafList.type)
        }
        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredLeafListTypes += sName to res
        return res
    }

    private val registeredChoiceObjectTypes: MutableMap<JQName, ObjectTypeDesign> = mutableMapOf()
    fun addChoiceObjectType(choice: ChoiceEffectiveStatement): ObjectTypeDesign {
        choice as ChoiceSchemaNode
        val sName = choice.getSymbolicName("ChoiceType")
        registeredChoiceObjectTypes[sName]?.let { return it }

        val choiceType = ObjectTypeDesign().apply {
            symbolicName = sName
            isIsAbstract = true
            copyDescription(choice)
            copyVisibleNames(choice)
            baseType = opcuaRef("BaseObjectType")
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += choiceType
        registeredChoiceObjectTypes += sName to choiceType
        choice.cases.forEach { (_, caseNode) -> addCaseObjectType(caseNode, choiceType.symbolicName) }

        return choiceType
    }

    private val registeredCaseObjectTypes: MutableMap<JQName, ObjectTypeDesign> = mutableMapOf()
    fun addCaseObjectType(case: CaseSchemaNode, choiceType: JQName): ObjectTypeDesign {
        case as CaseEffectiveStatement
        val sName = case.getSymbolicName("CaseType")
        registeredCaseObjectTypes[sName]?.let { return it }

        val res = ObjectTypeDesign().apply {
            symbolicName = sName
            copyDescription(case)
            copyVisibleNames(case)
            baseType = choiceType

            children = makeChildren(case.effectiveSubstatements(), null, null)
        }

        opcuaRootModel.objectTypeOrVariableTypeOrReferenceType += res
        registeredCaseObjectTypes += sName to res
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