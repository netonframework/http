package neton.http.internal

/**
 * Marks declarations that are public only so that other modules of this repository (`com.netonstream:http3`) can
 * share code with `com.netonstream:http`. They are not part of the library's API: they may change or disappear in
 * any release, and using them requires an explicit opt-in.
 */
@RequiresOptIn(
    message = "Internal API shared between the http modules of this repository; not for use outside it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalHttpApi
