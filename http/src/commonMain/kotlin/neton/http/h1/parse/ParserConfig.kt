package neton.http.h1.parse

/**
 * Parser configuration (httparse `lib.rs ParserConfig`). Every switch defaults to `false`.
 *
 * The seven httparse switches keep their reference meaning. [allowBareLf] is this library's addition (SPEC §3.1,
 * §3.9): httparse accepts a bare `LF` as a line ending everywhere; this library rejects it by default and
 * `allowBareLf = true` restores the reference behaviour exactly. A lone `CR` is always an error.
 *
 * Instances are immutable and may be shared between threads and parses.
 */
class ParserConfig(
    /**
     * Allow spaces and tabs between a header name and the colon, in responses only
     * (`allow_spaces_after_header_name_in_responses`). Requests never allow them.
     */
    val allowSpacesAfterHeaderNameInResponses: Boolean = false,
    /**
     * Allow obsolete line folding (obs-fold) in response header values
     * (`allow_obsolete_multiline_headers_in_responses`). The folded value keeps its `CR LF` and leading whitespace
     * bytes; a consumer should replace them with spaces. Requests never allow obs-fold.
     */
    val allowObsoleteMultilineHeadersInResponses: Boolean = false,
    /**
     * Allow runs of spaces (only `SP`, not HTAB/VT/FF/CR) as the request-line delimiters
     * (`allow_multiple_spaces_in_request_line_delimiters`).
     */
    val allowMultipleSpacesInRequestLineDelimiters: Boolean = false,
    /**
     * Allow runs of spaces (only `SP`) as the status-line delimiters
     * (`allow_multiple_spaces_in_response_status_delimiters`).
     */
    val allowMultipleSpacesInResponseStatusDelimiters: Boolean = false,
    /**
     * Allow whitespace before the first header name (`allow_space_before_first_header_name`); some browsers
     * ignore it (curl issue 11605).
     */
    val allowSpaceBeforeFirstHeaderName: Boolean = false,
    /**
     * Silently skip invalid header lines in responses (`ignore_invalid_headers_in_responses`), as browsers do.
     * A `NUL` byte or a lone `CR` found while skipping is still an error.
     */
    val ignoreInvalidHeadersInResponses: Boolean = false,
    /** The request counterpart of [ignoreInvalidHeadersInResponses] (`ignore_invalid_headers_in_requests`). */
    val ignoreInvalidHeadersInRequests: Boolean = false,
    /**
     * Accept a bare `LF` as a line ending (request line, status line, header lines, the final empty line and the
     * empty lines skipped before a message), as httparse does. Default `false`: only `CR LF` is accepted
     * (safety baseline, SPEC §3.9).
     */
    val allowBareLf: Boolean = false,
) {
    /** Parses a request with this configuration (`ParserConfig::parse_request`). See [ParsedRequest.parse]. */
    fun parseRequest(
        request: ParsedRequest,
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Int = request.parse(bytes, offset, length, this)

    /** Parses a response with this configuration (`ParserConfig::parse_response`). See [ParsedResponse.parse]. */
    fun parseResponse(
        response: ParsedResponse,
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Int = response.parse(bytes, offset, length, this)

    /** A copy with some switches changed. */
    fun copy(
        allowSpacesAfterHeaderNameInResponses: Boolean = this.allowSpacesAfterHeaderNameInResponses,
        allowObsoleteMultilineHeadersInResponses: Boolean = this.allowObsoleteMultilineHeadersInResponses,
        allowMultipleSpacesInRequestLineDelimiters: Boolean = this.allowMultipleSpacesInRequestLineDelimiters,
        allowMultipleSpacesInResponseStatusDelimiters: Boolean = this.allowMultipleSpacesInResponseStatusDelimiters,
        allowSpaceBeforeFirstHeaderName: Boolean = this.allowSpaceBeforeFirstHeaderName,
        ignoreInvalidHeadersInResponses: Boolean = this.ignoreInvalidHeadersInResponses,
        ignoreInvalidHeadersInRequests: Boolean = this.ignoreInvalidHeadersInRequests,
        allowBareLf: Boolean = this.allowBareLf,
    ): ParserConfig = ParserConfig(
        allowSpacesAfterHeaderNameInResponses, allowObsoleteMultilineHeadersInResponses,
        allowMultipleSpacesInRequestLineDelimiters, allowMultipleSpacesInResponseStatusDelimiters,
        allowSpaceBeforeFirstHeaderName, ignoreInvalidHeadersInResponses, ignoreInvalidHeadersInRequests, allowBareLf,
    )

    override fun toString(): String =
        "ParserConfig(allowSpacesAfterHeaderNameInResponses=$allowSpacesAfterHeaderNameInResponses, " +
            "allowObsoleteMultilineHeadersInResponses=$allowObsoleteMultilineHeadersInResponses, " +
            "allowMultipleSpacesInRequestLineDelimiters=$allowMultipleSpacesInRequestLineDelimiters, " +
            "allowMultipleSpacesInResponseStatusDelimiters=$allowMultipleSpacesInResponseStatusDelimiters, " +
            "allowSpaceBeforeFirstHeaderName=$allowSpaceBeforeFirstHeaderName, " +
            "ignoreInvalidHeadersInResponses=$ignoreInvalidHeadersInResponses, " +
            "ignoreInvalidHeadersInRequests=$ignoreInvalidHeadersInRequests, allowBareLf=$allowBareLf)"

    companion object {
        /** All switches off (httparse `ParserConfig::default()`, plus bare `LF` rejected). */
        val DEFAULT: ParserConfig = ParserConfig()
    }
}
