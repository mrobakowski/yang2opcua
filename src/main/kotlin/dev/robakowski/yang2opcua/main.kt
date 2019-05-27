@file:Suppress("UnstableApiUsage")

package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.ModelDesign
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext
import org.opendaylight.yangtools.yang.model.parser.api.YangParserFactory
import org.opendaylight.yangtools.yang.model.parser.api.YangSyntaxErrorException
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource
import org.opendaylight.yangtools.yang.parser.impl.YangParserFactoryImpl
import java.io.File
import java.time.Instant
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller

fun main() {

    val mainFile = YangTextSchemaSource.forFile(File("./yang/experimental/ieee/802.1/ieee802-dot1q-psfp.yang"))
    val libs = File("./yang/standard").walkBottomUp()
        .filter { it.isFile && it.extension == "yang" }
        .mapNotNull {
            try {
                YangTextSchemaSource.forFile(it)
            } catch (e: Exception) {
                println("unexpected error with $it\n${e.message}")
                null
            }
        }.toList()

    val parser = YangParserFactoryImpl().createParser().apply {
        addSource(mainFile)
        libs.forEach { lib ->
            try {
                addLibSource(lib)
            } catch (e: YangSyntaxErrorException) {
                println("syntax error: ${e.message}")
            }
        }
    }

//    val declaredModel = parser.buildDeclaredModel()
    val model = parser.buildEffectiveModel()

    val opcuaAnimalExampleModel = modelDesign {
        targetNamespace = "https://opcua.rocks/UA/animal/"
        targetXmlNamespace = targetNamespace
        targetVersion = "0.9.0"
        targetPublicationDate = Instant.now().toXmlCalendar()

        namespaces {
            namespace {
                name = "animal"
                prefix = name
                xmlNamespace = "${targetNamespace}Types.xsd"
                xmlPrefix = name

                value = "https://opcua.rocks/UA/animal/"
            }

            namespace {
                name = "OpcUa"
                version = "1.03"
                publicationDate = Instant.now().toString()
                prefix = "Opc.Ua"
                internalPrefix = "Opc.Ua.Server"
                xmlNamespace = "http://opcfoundation.org/UA/2008/02/Types.xsd"
                xmlPrefix = name

                value = "http://opcfoundation.org/UA/"
            }
        }
    }

    val ctxt = JAXBContext.newInstance(ModelDesign::class.java)
    val marshaler = ctxt.createMarshaller().apply { setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true) }

    marshaler.marshal(opcuaAnimalExampleModel, System.out)
}