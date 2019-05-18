package dev.robakowski.yang2opcua

import com.sun.org.apache.xerces.internal.jaxp.datatype.XMLGregorianCalendarImpl
import org.opcfoundation.ua.modeldesign.ModelDesign
import org.opcfoundation.ua.modeldesign.Namespace
import org.opcfoundation.ua.modeldesign.NamespaceTable
import java.time.Instant
import java.util.*

fun modelDesign(block: ModelDesign.() -> Unit) = ModelDesign().apply(block)

fun Instant.toXmlCalendar() =
    XMLGregorianCalendarImpl(GregorianCalendar().apply { timeInMillis = this@toXmlCalendar.toEpochMilli() })

fun ModelDesign.namespaces(block: NamespaceTable.() -> Unit) {
    this.namespaces = NamespaceTable().apply(block)
}

fun NamespaceTable.namespace(block: Namespace.() -> Unit) {
    namespace += Namespace().apply(block)
}