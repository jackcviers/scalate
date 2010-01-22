package org.fusesource.ssp

import scala.xml.Node
import javax.servlet.http._
import org.fusesource.ssp.util.{Lazy, XmlEscape}
import java.text.{DateFormat, NumberFormat}
import java.util.{Date, Locale}
import java.io._
import javax.servlet.{ServletOutputStream, ServletContext, RequestDispatcher, ServletException}
import java.lang.String
import collection.mutable.HashMap

class NoSuchAttributeException(val attribute: String) extends ServletException("No attribute called '" + attribute + "' was available in this SSP Page") {
}


class NoSuchTemplateException(val model: AnyRef, val view: String) extends ServletException("No '" + view + "' view template could be found for model object '" + model + "' of type: " + model.getClass.getCanonicalName) {
}
/**
 * The PageContext provides helper methods for interacting with the request, response, attributes and parameters
 */
case class PageContext(out: PrintWriter, request: HttpServletRequest, response: HttpServletResponse, servletContext: ServletContext) {
  private val resourceBeanAttribute = "it"

  var nullString = ""
  var viewPrefixes = List("WEB-INF", "")
  var viewPostfixes = List(".ssp")
  val defaultCharacterEncoding = "ISO-8859-1";


  private var _numberFormat = new Lazy(NumberFormat.getNumberInstance(locale))
  private var _percentFormat = new Lazy(NumberFormat.getPercentInstance(locale))
  private var _dateFormat = new Lazy(DateFormat.getDateInstance(DateFormat.FULL, locale))


  /**
   * Called after each page completes
   */
  def completed = {
    out.flush
  }

  /**
   * Returns the attribute of the given type or a            { @link NoSuchAttributeException } exception is thrown
   */
  def attribute[T](name: String): T = {
    val value = request.getAttribute(name)
    if (value != null) {
      value.asInstanceOf[T]
    }
    else {
      throw new NoSuchAttributeException(name)
    }
  }

  /**
   * Returns the attribute of the given name and type or the default value if it is not available
   */
  def attributeOrElse[T](name: String, defaultValue: T): T = {
    val value = request.getAttribute(name)
    if (value != null) {
      value.asInstanceOf[T]
    }
    else {
      defaultValue
    }
  }

  /**
   * Returns the JAXRS resource bean of the given type or a           { @link NoSuchAttributeException } exception is thrown
   */
  def resource[T]: T = {
    attribute[T](resourceBeanAttribute)
  }

  /**
   * Returns the JAXRS resource bean of the given type or the default value if it is not available
   */
  def resourceOrElse[T](defaultValue: T): T = {
    attributeOrElse(resourceBeanAttribute, defaultValue)
  }


  // Rendering methods

  /**
   * Includes the given page inside this page
   */
  def include(page: String): Unit = {
    getRequestDispatcher(page).include(request, response)
  }

  /**
   * Forwards this request to the given page
   */
  def forward(page: String): Unit = {
    getRequestDispatcher(page).forward(request, response)
  }


  private def getRequestDispatcher(path: String) = {
    val dispatcher = request.getRequestDispatcher(path)
    if (dispatcher == null) {
      throw new ServletException("No dispatcher available for path: " + path)
    }
    else {
      dispatcher
    }
  }

  class RequestWrapper(request: HttpServletRequest) extends HttpServletRequestWrapper(request) {
    //println("Creating requestWrapper: " + this + " with child request: " + request)

    override def getMethod() = {
      "GET";
    }

    val _attributes = new HashMap[String,Object]

    override def setAttribute(name: String, value: Object) = _attributes(name) = value

    override def getAttribute(name: String) =  _attributes.get(name).getOrElse(super.getAttribute(name))
  }

  class ResponseWrapper(response: HttpServletResponse, charEncoding: String = null) extends HttpServletResponseWrapper(response) {
    val sw = new StringWriter()
    val bos = new ByteArrayOutputStream()
    val sos = new ServletOutputStream() {
      def write(b: Int): Unit = {
        bos.write(b)
      }
    }
    var isWriterUsed = false
    var isStreamUsed = false
    var _status = 200


    override def getWriter(): PrintWriter = {
      if (isStreamUsed)
        throw new IllegalStateException("Attempt to import illegal Writer")
      isWriterUsed = true
      new PrintWriter(sw)
    }

    override def getOutputStream(): ServletOutputStream = {
      if (isWriterUsed)
        throw new IllegalStateException("Attempt to import illegal OutputStream")
      isStreamUsed = true
      sos
    }

    override def reset = {}

    override def resetBuffer = {}

    override def setContentType(x: String) = {} // ignore

    override def setLocale(x: Locale) = {} // ignore

    override def setStatus(status: Int): Unit = _status = status

    def getStatus() = _status

    def getString() = {
      if (isWriterUsed)
        sw.toString()
      else if (isStreamUsed) {
        if (charEncoding != null && !charEncoding.equals(""))
          bos.toString(charEncoding)
        else
          bos.toString(defaultCharacterEncoding)
      } else
        "" // target didn't write anything
    }
  }


  /**
   * Renders the view of the given model object, looking for the view in
   * packageName/className.viewName.ssp
   */
  def view(model: AnyRef, view: String = "index") {
    if (model == null) {
      throw new NullPointerException("No model object given!")
    }
    var flag = true
    var aClass = model.getClass
    while (flag && aClass != null && aClass != classOf[Object]) {

      resolveViewForType(model, view, aClass) match {
        case Some(dispatcher) =>
          flag = false
          out.flush

          val wrappedRequest = new RequestWrapper(request)
          val wrappedResponse = new ResponseWrapper(response)
          wrappedRequest.setAttribute("it", model)
          
          dispatcher.forward(wrappedRequest, wrappedResponse)
          val text = wrappedResponse.getString
          out.write(text)

        case _ => aClass = aClass.getSuperclass
      }
    }

    if (flag) {
      throw new NoSuchTemplateException(model, view)
    }
    // TODO now lets walk the interfaces...
  }

  private def resolveViewForType(model: AnyRef, view: String, aClass: Class[_]): Option[RequestDispatcher] = {
    for (prefix <- viewPrefixes; postfix <- viewPostfixes) {
      val path = aClass.getName.replace('.', '/') + "." + view + postfix
      val fullPath = if (prefix.isEmpty) {"/" + path} else {"/" + prefix + "/" + path}

      val url = servletContext.getResource(fullPath)
      if (url != null) {
        val dispatcher = request.getRequestDispatcher(fullPath)
        if (dispatcher != null) {
          return Some(dispatcher)
          //return Some(new RequestDispatcherWrapper(dispatcher, fullPath, hc, model))
        }
      }
    }
    None
  }


  /**
   * Converts the value to a string so it can be output on the screen, which uses the           { @link # nullString } value
   * for nulls
   */
  def toString(value: Any): String = {
    value match {
      case d: Date => dateFormat.format(d)
      case n: Number => numberFormat.format(n)
      case a => if (a == null) {nullString} else {a.toString}
    }
  }

  /**
   * Converts the value to a string so it can be output on the screen, which uses the           { @link # nullString } value
   * for nulls
   */
  def write(value: Any): Unit = {
    value match {
      case n: Node => out.print(n)
      case s: Seq[Node] => for (n <- s) {out.print(n)}
      case a => out.print(toString(a))
    }
  }

  /**
   * Converts the value to an XML escaped string; a           { @link Seq[Node] } or           { @link Node } is passed through as is.
   * A null value uses the           { @link # nullString } value to display nulls
   */
  def writeXmlEscape(value: Any): Unit = {
    value match {
      case n: Node => out.print(n)
      case s: Seq[Node] => for (n <- s) {out.print(n)}
      case a => write(XmlEscape.escape(toString(a)))
    }
  }


  /**
   * Returns the formatted string using the locale of the users request or the default locale if not available
   */
  def format(pattern: String, args: AnyRef*) = {
    String.format(locale, pattern, args: _*)
  }

  def percent(number: Number) = percentFormat.format(number)

  // Locale based formatters
  //
  // shame we can't use 'lazy var' for this cruft...
  def numberFormat: NumberFormat = _numberFormat()

  def numberFormat_=(value: NumberFormat): Unit = _numberFormat(value)

  def percentFormat: NumberFormat = _percentFormat()

  def percentFormat_=(value: NumberFormat): Unit = _percentFormat(value)

  def dateFormat: DateFormat = _dateFormat()

  def dateFormat_=(value: DateFormat): Unit = _dateFormat(value)


  def locale: Locale = {
    var locale = request.getLocale
    if (locale == null) {
      Locale.getDefault
    }
    else {
      locale
    }
  }
}

/**
 * Defines a bunch of helper methods available to SSP pages
 *
 * @version $Revision : 1.1 $
 */
abstract class Page extends HttpServlet {
  def createPageContext(out: PrintWriter, request: HttpServletRequest, response: HttpServletResponse) = {
    PageContext(out, request, response, getServletContext)
  }

  override def service(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val out = response.getWriter
    val pageContext = createPageContext(out, request, response)
    render(pageContext)
  }

  def render(pageContext: PageContext): Unit

}