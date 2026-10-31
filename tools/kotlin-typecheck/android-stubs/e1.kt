package com.chaquo.python
open class PyObject { fun callAttr(name: String, vararg args: Any?): PyObject = this; override fun toString() = "" }
class PyException : RuntimeException()
interface Platform
class Python { fun getModule(n: String): PyObject = PyObject()
    companion object { fun isStarted() = false; fun start(p: Platform) {}; fun getInstance() = Python() } }
