package com.overviewdocs.http

case class Response(
  statusCode: Int,

  /** Returns response body as bytes.
    *
    * We don't provide it as a String because in our case, that value is always
    * wrong. At time of writing, we only request from DocumentCloud, and it
    * returns the wrong encoding. See:
    *
    * * https://www.pivotaltracker.com/story/show/85536256
    * * https://github.com/documentcloud/documentcloud/pull/143
    * * https://github.com/documentcloud/documentcloud/issues/221
    *
    * Once DocumentCloud fixes this, we can change `bodyAsBytes` to `body`, a
    * String. But it might be a long time.
    */
  bodyBytes: Array[Byte],

  /** Returns the headers.
    *
    * The map keys are treated as case-insensitive.
    */
  headers: Map[String,Seq[String]]
)
