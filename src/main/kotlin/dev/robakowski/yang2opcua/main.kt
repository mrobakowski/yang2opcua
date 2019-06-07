@file:Suppress("UnstableApiUsage")

package dev.robakowski.yang2opcua

import org.opcfoundation.ua.modeldesign.ModelDesign
import org.opendaylight.yangtools.yang.model.parser.api.YangSyntaxErrorException
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource
import org.opendaylight.yangtools.yang.parser.impl.YangParserFactoryImpl
import java.io.File
import java.nio.file.FileSystems
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller


fun main() {
    val mainFile = YangTextSchemaSource.forFile(File("./yang/experimental/ieee/802.1/ieee802-dot1q-psfp.yang"))
    val dateRegex = Regex("@(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)")
    val libs = File("./yang/standard").walkBottomUp()
        .onEnter {
            val sep = FileSystems.getDefault().separator
            !it.toString().contains("yang${sep}standard${sep}mef") // files in mef folder cause the parser to break
        }
        .filter { it.isFile && it.extension == "yang" }
        .groupBy { it.path.split("@")[0] }
        .asSequence()
        .map { (_, v) ->
            v.singleOrNull() ?: v.map {
                dateRegex.find(it.path)!!.destructured.let { (year, month, day) ->
                    it to Triple(year.toInt(), month.toInt(), day.toInt())
                }
            }.maxWith(compareBy({ it.second.first }, { it.second.second }, { it.second.third }))
                .let { it!!.first }
        }
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

    val yangModel = parser.buildEffectiveModel()
    val opcuaModelBuilder = OpcuaModelBuilder(yangModel, "ieee802-dot1q-psfp")

    val opcuaModel = opcuaModelBuilder.build()

    val context = JAXBContext.newInstance(ModelDesign::class.java)
    val marshaller = context.createMarshaller().apply { setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true) }

    marshaller.marshal(opcuaModel, System.out)
    marshaller.marshal(opcuaModel, File("./build/ieee802-dot1q-psfp.Model.xml"))
}