/**
 * Provides classes for working with untrusted flow sources, sinks and taint propagators
 * from the `github.com/valyala/fasthttp` package.
 */

import go
private import semmle.go.security.RequestForgeryCustomizations

/**
 * Provides classes for working with the [fasthttp](github.com/valyala/fasthttp) package.
 */
module Fasthttp {
  /** Gets the v1 module path `github.com/valyala/fasthttp`. */
  string v1modulePath() { result = "github.com/valyala/fasthttp" }

  /** Gets the path for the root package of fasthttp. */
  string packagePath() { result = package(v1modulePath(), "") }

  /**
   * Provide models for sanitizer/Dangerous Functions of fasthttp.
   */
  module Functions {
    /**
     * A function that doesn't sanitize user-provided file paths.
     */
    class FileSystemAccess extends FileSystemAccess::Range, DataFlow::CallNode {
      FileSystemAccess() {
        exists(Function f |
          f.hasQualifiedName(packagePath(),
            [
              "SaveMultipartFile", "ServeFile", "ServeFileBytes", "ServeFileBytesUncompressed",
              "ServeFileUncompressed"
            ]) and
          this = f.getACall()
        )
      }

      override DataFlow::Node getAPathArgument() { result = this.getArgument(1) }
    }

    /**
     * A function that can be used as a sanitizer for XSS.
     */
    class HtmlQuoteSanitizer extends SharedXss::Sanitizer {
      HtmlQuoteSanitizer() {
        exists(DataFlow::CallNode c |
          c.getTarget()
              .hasQualifiedName(packagePath(),
                ["AppendHTMLEscape", "AppendHTMLEscapeBytes", "AppendQuotedArg"])
        |
          this = c.getArgument(1)
        )
      }
    }

    /**
     * A function that sends HTTP requests.
     *
     * Get* send a HTTP GET request.
     * Post send a HTTP POST request.
     * These functions first argument is a URL.
     */
    class RequestForgerySink extends RequestForgery::Sink {
      RequestForgerySink() {
        exists(Function f |
          f.hasQualifiedName(packagePath(), ["Get", "GetDeadline", "GetTimeout", "Post"]) and
          this = f.getACall().getArgument(1)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "URL" }
    }

    /**
     * A function that create initial connection to a TCP address.
     * Following Functions only accept TCP address + Port in their first argument.
     */
    class RequestForgerySinkDial extends RequestForgery::Sink {
      RequestForgerySinkDial() {
        exists(Function f |
          f.hasQualifiedName(packagePath(),
            ["Dial", "DialDualStack", "DialDualStackTimeout", "DialTimeout"]) and
          this = f.getACall().getArgument(0)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "TCP Addr + Port" }
    }
  }

  /**
   * Provide modeling for fasthttp.URI Type.
   */
  module URI {
    /**
     * The methods as Remote user controllable source which are part of the incoming URL.
     */
    class UntrustedFlowSource extends UntrustedFlowSource::Range instanceof DataFlow::Node {
      UntrustedFlowSource() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "URI",
            ["FullURI", "LastPathSegment", "Path", "PathOriginal", "QueryString", "String"]) and
          this = m.getACall().getResult(0)
        )
      }
    }
  }

  /**
   * Provide modeling for fasthttp.Args Type.
   */
  module Args {
    /**
     * The methods as Remote user controllable source which are part of the incoming URL Parameters.
     *
     * When support for lambdas has been implemented we should model "VisitAll".
     */
    class UntrustedFlowSource extends UntrustedFlowSource::Range instanceof DataFlow::Node {
      UntrustedFlowSource() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "Args",
            ["Peek", "PeekBytes", "PeekMulti", "PeekMultiBytes", "QueryString", "String"]) and
          this = m.getACall().getResult(0)
        )
      }
    }
  }

  /**
   * Provide modeling for fasthttp.TCPDialer Type.
   */
  module TcpDialer {
    /**
     * A method that create initial connection to a TCP address.
     * Provide Methods which can be used as dangerous RequestForgery Sinks.
     * Following Methods only accept TCP address + Port in their first argument.
     */
    class RequestForgerySinkDial extends RequestForgery::Sink {
      RequestForgerySinkDial() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "TCPDialer",
            ["Dial", "DialDualStack", "DialDualStackTimeout", "DialTimeout"]) and
          this = m.getACall().getArgument(0)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "TCP Addr + Port" }
    }
  }

  /**
   * Provide modeling for fasthttp.Client Type.
   */
  module Client {
    /**
     * A method that sends HTTP requests.
     * Get* send a HTTP GET request.
     * Post send a HTTP POST request.
     * these Functions first arguments is a URL.
     */
    class RequestForgerySink extends RequestForgery::Sink {
      RequestForgerySink() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "Client", ["Get", "GetDeadline", "GetTimeout", "Post"]) and
          this = m.getACall().getArgument(1)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "URL" }
    }
  }

  /**
   * Provide modeling for fasthttp.HostClient Type.
   */
  module HostClient {
    /**
     * A method that sends HTTP requests.
     * Get* send a HTTP GET request.
     * Post send a HTTP POST request.
     * these Functions first arguments is a URL.
     */
    class RequestForgerySink extends RequestForgery::Sink {
      RequestForgerySink() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "HostClient",
            ["Get", "GetDeadline", "GetTimeout", "Post"]) and
          this = m.getACall().getArgument(1)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "URL" }
    }
  }

  /**
   * Provide modeling for fasthttp.Response Type.
   */
  module Response {
    /**
     * A Method That send files from its input.
     * It does not check the input path against path traversal attacks, So it is a dangerous method.
     */
    class FileSystemAccess extends FileSystemAccess::Range, DataFlow::CallNode {
      FileSystemAccess() {
        exists(Method mcn |
          mcn.hasQualifiedName(packagePath(), "Response", "SendFile") and
          this = mcn.getACall()
        )
      }

      override DataFlow::Node getAPathArgument() { result = this.getArgument(0) }
    }

    /**
     * The methods that can write to HTTP Response Body.
     * These methods can be dangerous if they are user controllable.
     */
    class HttpResponseBodySink extends SharedXss::Sink {
      HttpResponseBodySink() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "Response",
            [
              "AppendBody", "AppendBodyString", "SetBody", "SetBodyRaw", "SetBodyStream",
              "SetBodyString"
            ]) and
          this = m.getACall().getArgument(0)
        )
        or
        exists(Method write, DataFlow::CallNode writeCall |
          write.hasQualifiedName("io", "Writer", "Write") and
          writeCall = write.getACall() and
          ResponseBodyWriterFlow::flowsTo(writeCall.getReceiver()) and
          this = writeCall.getArgument(0)
        )
      }
    }

    private predicate responseBodyWriterResult(DataFlow::Node src) {
      exists(Method responseBodyWriter |
        responseBodyWriter.hasQualifiedName(packagePath(), "Response", "BodyWriter") and
        src = responseBodyWriter.getACall().getResult(0)
      )
    }

    private module ResponseBodyWriterFlow = DataFlow::SimpleGlobal<responseBodyWriterResult/1>;
  }

  /**
   * Provide modeling for fasthttp.Request Type.
   */
  module Request {
    /**
     * The methods as Remote user controllable source which can be many part of request.
     */
    class UntrustedFlowSource extends UntrustedFlowSource::Range instanceof DataFlow::Node {
      UntrustedFlowSource() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "Request",
            [
              "Body", "BodyGunzip", "BodyInflate", "BodyStream", "BodyUnbrotli", "BodyUncompressed",
              "Host", "RequestURI"
            ]) and
          this = m.getACall().getResult(0)
        )
      }
    }

    /**
     * A method that create the URL and Host parts of a `Request` type.
     *
     * This instance of `Request` type can be used in some functions/methods
     * like `func Do(req *Request, resp *Response) error` that will lead to server side request forgery vulnerability.
     */
    class RequestForgerySink extends RequestForgery::Sink {
      RequestForgerySink() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "Request",
            ["SetHost", "SetHostBytes", "SetRequestURI", "SetRequestURIBytes", "SetURI"]) and
          this = m.getACall().getArgument(0)
        )
      }

      override DataFlow::Node getARequest() { result = this }

      override string getKind() { result = "URL" }
    }
  }

  /**
   * Provide modeling for fasthttp.RequestCtx Type.
   */
  module RequestCtx {
    /**
     * The Methods that don't sanitize user provided file paths.
     */
    class FileSystemAccess extends FileSystemAccess::Range, DataFlow::CallNode {
      FileSystemAccess() {
        exists(Method mcn |
          mcn.hasQualifiedName(packagePath(), "RequestCtx", ["SendFile", "SendFileBytes"]) and
          this = mcn.getACall()
        )
      }

      override DataFlow::Node getAPathArgument() { result = this.getArgument(0) }
    }

    /**
     * The Methods that can be dangerous if they take user controlled URL as their first argument.
     */
    class Redirect extends Http::Redirect::Range, DataFlow::CallNode {
      Redirect() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "RequestCtx", ["Redirect", "RedirectBytes"]) and
          this = m.getACall()
        )
      }

      override DataFlow::Node getUrl() { result = this.getArgument(0) }

      override Http::ResponseWriter getResponseWriter() { none() }
    }

    /**
     * The methods as Remote user controllable source which are generally related to HTTP request.
     *
     * When support for lambdas has been implemented we should model "VisitAll", "VisitAllCookie", "VisitAllInOrder", "VisitAllTrailer".
     */
    class UntrustedFlowSource extends UntrustedFlowSource::Range instanceof DataFlow::Node {
      UntrustedFlowSource() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "RequestCtx",
            [
              "Host", "Path", "PostBody", "Referer", "RequestBodyStream", "RequestURI", "String",
              "UserAgent"
            ]) and
          this = m.getACall().getResult(0)
        )
      }
    }

    /**
     * The methods that can write to HTTP Response Body.
     * These methods can be dangerous if they are user controllable.
     */
    class HttpResponseBodySink extends SharedXss::Sink {
      HttpResponseBodySink() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "RequestCtx", ["Success", "SuccessString"]) and
          this = m.getACall().getArgument(1)
        )
      }
    }
  }

  /**
   * Provide Methods of fasthttp.RequestHeader which mostly used as remote user controlled sources.
   */
  module RequestHeader {
    /**
     * The methods as Remote user controllable source which are mostly related to HTTP Request Headers.
     *
     * When support for lambdas has been implemented we should model "VisitAll", "VisitAllCookie", "VisitAllInOrder", "VisitAllTrailer".
     */
    class UntrustedFlowSource extends UntrustedFlowSource::Range instanceof DataFlow::Node {
      UntrustedFlowSource() {
        exists(Method m |
          m.hasQualifiedName(packagePath(), "RequestHeader",
            [
              "ContentEncoding", "ContentType", "Cookie", "CookieBytes", "Header", "Host",
              "MultipartFormBoundary", "Peek", "PeekAll", "PeekBytes", "PeekKeys",
              "PeekTrailerKeys", "RawHeaders", "Referer", "RequestURI", "String", "TrailerHeader",
              "UserAgent"
            ]) and
          this = m.getACall().getResult(0)
        )
      }
    }
  }
}
