package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.ModelDesign
import java.time.Instant
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller

fun main() {
    val model = modelDesign {
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

    marshaler.marshal(model, System.out)
}