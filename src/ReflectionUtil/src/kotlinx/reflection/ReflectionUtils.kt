package kotlinx.reflection

import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.internal.KClassImpl
import kotlin.reflect.jvm.internal.impl.descriptors.ClassDescriptor
import kotlin.reflect.jvm.internal.impl.descriptors.ClassKind
import kotlin.reflect.jvm.internal.impl.descriptors.ValueParameterDescriptor
import kotlin.reflect.jvm.internal.impl.load.java.reflect.ReflectPackage
import kotlin.reflect.jvm.internal.impl.utils.UtilsPackage
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.kotlin

object ReflectionCache {
    val objects : MutableMap<Class<*>, Any> = ConcurrentHashMap()
    val companionObjects : MutableMap<Class<*>, Any> = ConcurrentHashMap()
    val consMetadata : MutableMap<Class<*>, Triple<Constructor<*>, Array<Class<*>>, List<ValueParameterDescriptor>>> = ConcurrentHashMap()
    val primaryProperites : MutableMap<Class<*>, List<String>> = ConcurrentHashMap()
    val propertyGetters : MutableMap<Pair<KClass<*>, String>, KProperty1<Any, Any?>?> = ConcurrentHashMap()
}

private object NullMask
private fun Any.unmask():Any? = if (this == NullMask) null else this

fun Class<*>.objectInstance0(): Any? {
    return ReflectionCache.objects.getOrPut(this) {
        try {
            val field = getDeclaredField("INSTANCE\$")
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) {
                if (!field.isAccessible()) {
                    field.setAccessible(true)
                }
                field[null]!!
            }
            else NullMask
        }
        catch (e: NoSuchFieldException) {
            NullMask
        }
    }.unmask()
}

fun Class<*>.objectInstance(): Any? {
    return ReflectionCache.objects.getOrPut(this) {
        getFields().firstOrNull {
            with(it.getType().kotlin as KClassImpl<*>) {
                __descriptor.getKind() == ClassKind.OBJECT && !__descriptor.isCompanionObject()
            }
        }?.get(null) ?: NullMask
    }.unmask()
}

fun Class<*>.companionObjectInstance(): Any? {
    return ReflectionCache.companionObjects.getOrPut(this) {
        getFields().firstOrNull { (it.getType().kotlin as KClassImpl<*>).__descriptor.isCompanionObject() }?.get(null) ?: NullMask
    }.unmask()
}

@suppress("UNCHECKED_CAST")
fun <T> KClass<out T>.propertyGetter(property: String): KProperty1<Any, *>? {
    return ReflectionCache.propertyGetters.getOrPut(Pair(this, property)) {
        properties.singleOrNull {
            property == it.javaField?.getName() ?: it.javaGetter?.getName()?.removePrefix("get")?.decapitalize()
        } as KProperty1<Any, *>?
    }
}

fun Any.propertyValue(property: String): Any? {
    val getter = javaClass.kotlin.propertyGetter(property) ?: error("Invalid property ${property} on type ${javaClass.getName()}")
    return getter.get(this)
}

private fun Class<*>.consMetaData(): Triple<Constructor<*>, Array<Class<*>>, List<ValueParameterDescriptor>> {
    return ReflectionCache.consMetadata.getOrPut(this) {
        val cons = primaryConstructor() ?: error("Expecting single constructor for the bean")
        val consDesc = (this.kotlin as KClassImpl<*>).__descriptor.getUnsubstitutedPrimaryConstructor()!!
        return Triple(cons, cons.getParameterTypes(), consDesc.getValueParameters())
    }
}

public class MissingArgumentException(message: String) : RuntimeException(message)

@suppress("UNCHECKED_CAST")
fun <T> Class<out T>.buildBeanInstance(allParams: Map<String,String>): T {
    objectInstance()?.let {
        return it as T
    }

    val (ktor, paramTypes, valueParams) = consMetaData()

    val args = valueParams.mapIndexed { i, param ->
        allParams[param.getName().asString()]?.let {
            Serialization.deserialize(it, paramTypes[i] as Class<Any>)
        } ?: if (param.getType().isMarkedNullable()) {
            null
        } else {
            throw MissingArgumentException("Required argument '${param.getName().asString()}' is missing, available params: $allParams")

        }
    }.toTypedArray()

    return ktor.newInstance(*args) as T
}

fun Any.primaryProperties() : List<String> {
    return ReflectionCache.primaryProperites.getOrPut(javaClass) {
        (javaClass.kotlin as KClassImpl<*>).__descriptor.getUnsubstitutedPrimaryConstructor()?.getValueParameters()?.map { it.getName().asString() }?.toList() ?: emptyList()
    }
}

@suppress("UNCHECKED_CAST")
fun <T> Class<out T>.primaryConstructor() : Constructor<T>? {
    return getConstructors().singleOrNull() as? Constructor<T>
}

public fun Class<*>.isEnumClass(): Boolean = javaClass<Enum<*>>().isAssignableFrom(this)

fun ClassLoader.findClasses(prefix: String, cache: MutableMap<Pair<Int, String>, List<Class<*>>>) : List<Class<*>> {
    synchronized(cache) {
        return cache.getOrPut(this.hashCode() to prefix) {
            scanForClasses(prefix)
        }
    }
}

fun ClassLoader.scanForClasses(prefix: String) : List<Class<*>> {
    val urls = arrayListOf<URL>()
    val clazzz = arrayListOf<Class<*>>()
    val path = prefix.replace(".", "/")
    val enumeration = this.getResources(path)
    while(enumeration.hasMoreElements()) {
        urls.add(enumeration.nextElement())
    }
    clazzz.addAll(urls.map {
        it.scanForClasses(prefix, this@scanForClasses)
    }.flatten())
    return clazzz
}

private fun URL.scanForClasses(prefix: String = "", classLoader: ClassLoader): List<Class<*>> {
    return when {
        getProtocol() == "jar" -> JarFile(urlDecode(toExternalForm().substringAfter("file:").substringBeforeLast("!"))).scanForClasses(prefix, classLoader)
        else -> File(urlDecode(getPath())).scanForClasses(prefix, classLoader)
    }
}

private fun String.packageToPath() = replace(".", File.separator) + File.separator

private fun File.scanForClasses(prefix: String, classLoader: ClassLoader): List<Class<*>> {
    val path = prefix.packageToPath()
    return FileTreeWalk(this, filter = {
        it.isDirectory() || (it.isFile() && it.extension == "class")
    }).toList()
    .filter{
        it.isFile() && it.getAbsolutePath().contains(path)
    }.map {
        ReflectPackage.tryLoadClass(classLoader, prefix +"." + it.getAbsolutePath().substringAfterLast(path).removeSuffix(".class").replace(File.separator, "."))
    }.filterNotNull().toList()
}

private fun JarFile.scanForClasses(prefix: String, classLoader: ClassLoader): List<Class<*>> {
    val classes = arrayListOf<Class<*>>()
    val path = prefix.replace(".", "/") + "/"
    val entries = this.entries()
    while(entries.hasMoreElements()) {
        entries.nextElement().let {
            if (!it.isDirectory() && it.getName().endsWith(".class") && it.getName().contains(path)) {
                UtilsPackage.addIfNotNull(classes, ReflectPackage.tryLoadClass(classLoader, prefix + "." + it.getName().substringAfterLast(path).removeSuffix(".class").replace("/", ".")))
            }
        }
    }
    return classes
}

@suppress("UNCHECKED_CAST")
fun <T> Iterable<Class<*>>.filterIsAssignable(clazz: Class<T>): List<Class<T>> = filter { clazz.isAssignableFrom(it) } as List<Class<T>>

@suppress("UNCHECKED_CAST")
inline fun <reified T> Iterable<Class<*>>.filterIsAssignable(): List<Class<T>> = filterIsAssignable(javaClass<T>())

val KClassImpl<*>.__descriptor: ClassDescriptor get() = ReflectionUtil.getClassDescriptor(this)